package com.example.stormcast

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
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
        box.addView(ctx.body("Checking NHC active storms\u2026"))
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
                        box.addView(ctx.card(stormSummary(s), 0xFFEF4444.toInt()))
                        box.addView(ctx.actionButton("\uD83D\uDDFA  View cone & track map", true) { openConeTrackMap(s) })
                        box.addView(ctx.actionButton("\uD83D\uDCC4  Public advisory") {
                            showText("Public advisory \u2014 ${s.optString("name")}",
                                s.optJSONObject("publicAdvisory")?.optString("url"))
                        })
                        box.addView(ctx.actionButton("\uD83D\uDCC4  Forecast advisory") {
                            showText("Forecast advisory \u2014 ${s.optString("name")}",
                                s.optJSONObject("forecastAdvisory")?.optString("url"))
                        })
                        box.addView(ctx.actionButton("\uD83D\uDCC4  Forecast discussion") {
                            showText("Forecast discussion \u2014 ${s.optString("name")}",
                                s.optJSONObject("forecastDiscussion")?.optString("url"))
                        })
                        box.addView(ctx.actionButton("\uD83D\uDCC4  Wind speed probabilities") {
                            showText("Wind speed probabilities \u2014 ${s.optString("name")}",
                                s.optJSONObject("windSpeedProbabilities")?.optString("url"))
                        })
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
        val dirTxt = if (dir.isNaN()) "?" else "${dir.toInt()}\u00B0 (${cardinal(dir)})"
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
        Toast.makeText(ctx, "Loading cone & track\u2026", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val shapes = ArrayList<GeoShape>()
                for (u in listOf(trackUrl, coneUrl)) {
                    if (u.isNullOrBlank() || !u.startsWith("http")) continue
                    extractKml(Api.getBytes(u))?.let { shapes.addAll(parseKmlShapes(it)) }
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

    private enum class GeoKind { POLYGON, LINE, POINT }

    private class GeoShape(val kind: GeoKind, val pts: List<Pair<Double, Double>>, val label: String)

    private fun parseKmlShapes(kml: String): List<GeoShape> {
        val opts = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        val placemarkRe = Regex("<Placemark>(.*?)</Placemark>", opts)
        val coordRe = Regex("<coordinates>(.*?)</coordinates>", opts)
        val fcstRe = Regex("<Data name=\"fcstpd\">\\s*<value>([^<]*)</value>", opts)
        val hrRe = Regex("(\\d+)\\s*hr", opts)
        val shapes = ArrayList<GeoShape>()
        for (pm in placemarkRe.findAll(kml)) {
            val block = pm.groupValues[1]
            val kind = when {
                block.contains("<Polygon", true) -> GeoKind.POLYGON
                block.contains("<LineString", true) -> GeoKind.LINE
                else -> GeoKind.POINT
            }
            val hours = fcstRe.find(block)?.groupValues?.get(1)?.trim()?.toIntOrNull()
                ?: hrRe.find(block)?.groupValues?.get(1)?.trim()?.toIntOrNull()
            val label = if (hours != null && hours > 0) "$hours hr" else "Current"
            for (cm in coordRe.findAll(block)) {
                val pts = ArrayList<Pair<Double, Double>>()
                for (tok in cm.groupValues[1].trim().split(Regex("\\s+"))) {
                    if (tok.isBlank()) continue
                    val c = tok.split(",")
                    if (c.size >= 2) {
                        val lo0 = c[0].toDoubleOrNull() ?: continue
                        val la = c[1].toDoubleOrNull() ?: continue
                        var lo = lo0
                        if (lo > 180.0) lo -= 360.0
                        if (lo < -180.0) lo += 360.0
                        pts.add(lo to la)
                    }
                }
                if (pts.isNotEmpty()) shapes.add(GeoShape(kind, pts, label))
            }
        }
        return shapes
    }

    private fun mapHtml(
        name: String,
        shapes: List<GeoShape>,
        lat: Double,
        lon: Double
    ): String {
        val data = JSONArray()
        for (sh in shapes) {
            val pts = JSONArray()
            for (p in sh.pts) pts.put(JSONArray().put(p.first).put(p.second))
            data.put(
                JSONObject()
                    .put("kind", sh.kind.name.lowercase())
                    .put("pts", pts)
                    .put("label", sh.label)
            )
        }
        return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
        <style>html,body,#map{margin:0;padding:0;height:100%;background:#0B1220}</style>
        </head>
        <body>
        <div id="map"></div>
        <script>
        var data = __DATA__;
        var map = L.map('map');
        L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
          attribution: '&copy; OpenStreetMap contributors &copy; CARTO',
          subdomains: 'abcd', maxZoom: 19
        }).addTo(map);
        var all = [];
        var points = [];
        data.forEach(function(sh) {
          var ll = sh.pts.map(function(p) { return [p[1], p[0]]; });
          ll.forEach(function(q){ all.push(q); });
          if (sh.kind === 'polygon') {
            L.polygon(ll, {color: '#F87171', weight: 1.5, fillColor: '#EF4444', fillOpacity: 0.15}).addTo(map);
          } else if (sh.kind === 'line') {
            L.polyline(ll, {color: '#FBBF24', weight: 4, opacity: 0.95}).addTo(map);
          } else {
            points.push({ll: ll[0], label: sh.label});
            L.circleMarker(ll[0], {radius: 6, color: '#FFFFFF', weight: 2, fillColor: '#EF4444', fillOpacity: 1})
              .addTo(map)
              .bindTooltip(sh.label, {direction: 'top', offset: [0, -6]});
          }
        });
        if (points.length > 1) {
          L.polyline(points.map(function(p){ return p.ll; }),
            {color: '#FBBF24', weight: 2, dashArray: '2 8', opacity: 0.9}).addTo(map);
        }
        L.circleMarker([__LAT__, __LON__], {
          radius: 9, color: '#FFFFFF', fillColor: '#DC2626', fillOpacity: 1, weight: 3
        }).addTo(map).bindTooltip('Current position', {direction: 'top', offset: [0, -8]});
        all.push([__LAT__, __LON__]);
        var legend = L.control({position: 'bottomright'});
        legend.onAdd = function() {
          var d = L.DomUtil.create('div');
          d.style.cssText = 'background:rgba(10,16,30,.85);color:#cbd5e1;padding:8px 12px;border-radius:8px;font:12px sans-serif;line-height:1.7';
          d.innerHTML = '<span style="color:#F87171">&#9644;</span> Cone of uncertainty<br>' +
                        '<span style="color:#FBBF24">&#9644;</span> Forecast track';
          return d;
        };
        legend.addTo(map);
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
            .setTitle("$name \u2014 cone & track")
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
        Toast.makeText(ctx, "Loading\u2026", Toast.LENGTH_SHORT).show()
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
