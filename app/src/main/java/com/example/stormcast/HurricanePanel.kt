package com.example.stormcast

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

class HurricanePanel(private val ctx: Context) : Panel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private fun dp(v: Int) = ctx.dp(v)

    private val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(16))
    }

    override val root: View = ScrollView(ctx).apply {
        setFillViewport(true)
        addView(box)
    }

    override fun refresh(lat: Double, lon: Double) {
        job?.cancel()
        box.removeAllViews()
        box.addView(ctx.body("Checking NHC active storms…"))
        job = scope.launch {
            try {
                val data = Api.getJson("https://www.nhc.noaa.gov/CurrentStorms.json")
                val storms = data.optJSONArray("activeStorms")
                withContext(Dispatchers.Main) {
                    box.removeAllViews()
                    box.addView(ctx.heading("Active tropical cyclones (NOAA NHC)"))
                    if (storms == null || storms.length() == 0) {
                        box.addView(ctx.body("No active tropical cyclones currently tracked by NOAA NHC in any basin."))
                        return@withContext
                    }
                    for (i in 0 until storms.length()) {
                        val s = storms.getJSONObject(i)
                        box.addView(ctx.card(stormSummary(s), 0xFFD32F2F.toInt()))
                        addButton("🗺  Cone & track map") { openConeTrackMap(s) }
                        addButton("📄  Public advisory") {
                            showText("Public advisory — ${s.optString("name")}",
                                s.optJSONObject("publicAdvisory")?.optString("url"))
                        }
                        addButton("📄  Forecast advisory") {
                            showText("Forecast advisory — ${s.optString("name")}",
                                s.optJSONObject("forecastAdvisory")?.optString("url"))
                        }
                        addButton("📄  Forecast discussion") {
                            showText("Forecast discussion — ${s.optString("name")}",
                                s.optJSONObject("forecastDiscussion")?.optString("url"))
                        }
                        addButton("📄  Wind speed probabilities") {
                            showText("Wind speed probabilities — ${s.optString("name")}",
                                s.optJSONObject("windSpeedProbabilities")?.optString("url"))
                        }
                    }
                    box.addView(ctx.body("Advisories are issued every 6 hours (every 3 hours when watches or warnings are in effect)."))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    box.removeAllViews()
                    box.addView(ctx.heading("NHC data unavailable"))
                    box.addView(ctx.body(e.message ?: "Network error"))
                }
            }
        }
    }

    private fun advisoryNumber(s: JSONObject): String =
        s.optJSONObject("publicAdvisory")?.optString("advNum")
            ?: s.optJSONObject("forecastAdvisory")?.optString("advNum")
            ?: ""

    private fun stormSummary(s: JSONObject): String {
        val cls = when (s.optString("classification")) {
            "HU" -> "Hurricane"
            "TS" -> "Tropical Storm"
            "TD" -> "Tropical Depression"
            "STD" -> "Subtropical Depression"
            "STS" -> "Subtropical Storm"
            "PSTD", "PS" -> "Post-Tropical Cyclone"
            else -> s.optString("classification")
        }
        val dir = s.optDouble("movementDir", Double.NaN)
        val dirTxt = if (dir.isNaN()) "?" else "${dir.toInt()}° (${cardinal(dir)})"
        return buildString {
            append(cls).append(" ").append(s.optString("name"))
                .append("  (").append(s.optString("id")).append(")\n\n")
            advisoryNumber(s).takeIf { it.isNotBlank() }
                ?.let { append("Advisory #").append(it).append("\n") }
            append("Intensity: ").append(s.optString("intensity")).append(" kt\n")
            append("Pressure: ").append(s.optString("pressure")).append(" mb\n")
            append("Position: ").append(s.optString("latitude"))
                .append(", ").append(s.optString("longitude")).append("\n")
            append("Movement: ").append(dirTxt)
                .append(" at ").append(s.optString("movementSpeed")).append(" kt\n")
            append("Last update: ").append(s.optString("lastUpdate"))
        }
    }

    private fun cardinal(deg: Double): String {
        val dirs = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return dirs[((deg % 360 + 360) % 360 / 22.5).toInt() % 16]
    }

    private fun addButton(label: String, onClick: () -> Unit) {
        box.addView(Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(20), dp(8), dp(20), 0) }
        })
    }

    // ---------------------------------------------------------------- in-app cone & track map

    private fun openConeTrackMap(s: JSONObject) {
        val trackUrl = s.optJSONObject("forecastTrack")?.optString("kmzFile")
        val coneUrl = s.optJSONObject("trackCone")?.optString("kmzFile")
        val name = s.optString("name")
        val lat = s.optDouble("latitudeNumeric", 0.0)
        val lon = s.optDouble("longitudeNumeric", 0.0)
        if (trackUrl.isNullOrBlank() && coneUrl.isNullOrBlank()) {
            Toast.makeText(ctx, "No map data available yet", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(ctx, "Loading cone & track…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val shapes = ArrayList<List<Pair<Double, Double>>>()
                for (u in listOf(trackUrl, coneUrl)) {
                    if (u.isNullOrBlank() || !u.startsWith("http")) continue
                    extractKml(Api.getBytes(u))?.let { shapes.addAll(parseKml(it)) }
                }
                if (shapes.isEmpty()) throw IOException("No track data available for $name yet")
                withContext(Dispatchers.Main) {
                    showMapDialog(name, mapHtml(name, shapes, lat, lon))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, e.message ?: "Failed to load map", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun extractKml(bytes: ByteArray): String? {
        return try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                var entry = z.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".kml", true)) {
                        return z.readBytes().toString(Charsets.UTF_8)
                    }
                    entry = z.nextEntry
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseKml(kml: String): List<List<Pair<Double, Double>>> {
        val shapes = ArrayList<List<Pair<Double, Double>>>()
        val re = Regex(
            "<coordinates>(.*?)</coordinates>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        for (m in re.findAll(kml)) {
            val pts = ArrayList<Pair<Double, Double>>()
            for (tok in m.groupValues[1].trim().split(Regex("\\s+"))) {
                if (tok.isBlank()) continue
                val c = tok.split(",")
                if (c.size >= 2) {
                    val lon = c[0].toDoubleOrNull()
                    val lat = c[1].toDoubleOrNull()
                    if (lon != null && lat != null) pts.add(lon to lat)
                }
            }
            if (pts.isNotEmpty()) shapes.add(pts)
        }
        return shapes
    }

    private fun mapHtml(
        name: String,
        shapes: List<List<Pair<Double, Double>>>,
        lat: Double,
        lon: Double
    ): String {
        val data = JSONArray()
        for (sh in shapes) {
            val arr = JSONArray()
            for (p in sh) arr.put(JSONArray().put(p.first).put(p.second))
            data.put(arr)
        }
        return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
        <style>html,body,#map{margin:0;padding:0;height:100%;background:#111}</style>
        </head>
        <body>
        <div id="map"></div>
        <script>
        var data = __DATA__;
        var map = L.map('map');
        L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
          attribution: '(c) OpenStreetMap contributors'
        }).addTo(map);
        var all = [];
        data.forEach(function(sh) {
          var ll = sh.map(function(p) { return [p[1], p[0]]; });
          if (ll.length > 40) {
            L.polygon(ll, {color: '#F44336', weight: 2, fillOpacity: 0.15}).addTo(map);
          } else if (ll.length > 1) {
            L.polyline(ll, {color: '#FFC107', weight: 4, opacity: 0.9}).addTo(map);
          } else {
            L.circleMarker(ll[0], {radius: 6, color: '#F44336', fillOpacity: 1}).addTo(map);
          }
          all = all.concat(ll);
        });
        L.circleMarker([__LAT__, __LON__], {
          radius: 9, color: '#FFFFFF', fillColor: '#D32F2F', fillOpacity: 1, weight: 3
        }).addTo(map);
        if (all.length > 0) { map.fitBounds(L.latLngBounds(all).pad(0.15)); }
        else { map.setView([__LAT__, __LON__], 5); }
        </script>
        </body>
        </html>
        """.trimIndent()
            .replace("__DATA__", data.toString())
            .replace("__LAT__", fmt(lat))
            .replace("__LON__", fmt(lon))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showMapDialog(name: String, html: String) {
        val web = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            loadDataWithBaseURL("https://www.openstreetmap.org/", html, "text/html", "UTF-8", null)
        }
        val dlg = AlertDialog.Builder(ctx)
            .setTitle("$name — cone & track")
            .setView(web)
            .setPositiveButton("Close", null)
            .create()
        dlg.show()
        dlg.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    // ---------------------------------------------------------------- in-app advisory text

    private fun showText(title: String, url: String?) {
        if (url.isNullOrBlank() || !url.startsWith("http")) {
            Toast.makeText(ctx, "Not available yet", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(ctx, "Loading…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val raw = Api.getText(url)
                val text = cleanHtml(raw)
                withContext(Dispatchers.Main) {
                    val tv = TextView(ctx).apply {
                        textSize = 13f
                        typeface = Typeface.MONOSPACE
                        setPadding(dp(16), dp(12), dp(16), dp(8))
                        setTextIsSelectable(true)
                        this.text = text
                    }
                    AlertDialog.Builder(ctx)
                        .setTitle(title)
                        .setView(ScrollView(ctx).apply { addView(tv) })
                        .setPositiveButton("Close", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, e.message ?: "Failed to load", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun cleanHtml(raw: String): String {
        return raw
            .replace(Regex("(?s)<script.*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("(?s)<style.*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("(?s)<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n[ \\t]*\n+"), "\n\n")
            .trim()
    }
}
