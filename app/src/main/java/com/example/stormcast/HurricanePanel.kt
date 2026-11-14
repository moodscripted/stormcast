package com.example.stormcast

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class HurricanePanel(private val ctx: Context) : Panel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var userLat = 0.0
    private var userLon = 0.0
    private fun dp(v: Int) = ctx.dp(v)

    private val leafletCss: String by lazy {
        ctx.assets.open("leaflet.css").bufferedReader().use { it.readText() }
    }

    private val leafletJs: String by lazy {
        ctx.assets.open("leaflet.js").bufferedReader().use { it.readText() }
    }

    private val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(16))
    }

    override val root: View = ScrollView(ctx).apply {
        setFillViewport(true)
        addView(box)
    }

    override fun refresh(lat: Double, lon: Double) {
        userLat = lat
        userLon = lon
        job?.cancel()
        box.removeAllViews()
        box.addView(ctx.body("Checking NHC active storms\u2026"))
        job = scope.launch {
            try {
                val data = Api.getJson("https://www.nhc.noaa.gov/CurrentStorms.json")
                val storms = data.optJSONArray("activeStorms")
                withContext(Dispatchers.Main) {
                    box.removeAllViews()
                    Api.takeStaleNote()?.let { box.addView(ctx.body("\uD83D\uDCE1  $it")) }
                    box.addView(ctx.heading("Active tropical cyclones (NOAA NHC)"))
                    if (storms == null || storms.length() == 0) {
                        box.addView(ctx.body("No active tropical cyclones currently tracked by NOAA NHC in any basin."))
                        addOutlook()
                        return@withContext
                    }
                    for (i in 0 until storms.length()) {
                        val s = storms.getJSONObject(i)
                        box.addView(ctx.card(stormSummary(s), 0xFFEF4444.toInt()))
                        addDistance(s)
                        box.addView(ctx.actionButton("\uD83D\uDDFA  View cone & track map", true) { openConeTrackMap(s) })
                        box.addView(ctx.actionButton("\uD83D\uDCC4  Public advisory") {
                            showText("Public advisory \u2014 ${s.optString("name")}",
                                s.optJSONObject("publicAdvisory")?.optString("url"), true)
                        })
                        box.addView(ctx.actionButton("\uD83D\uDCC4  Forecast advisory") {
                            showText("Forecast advisory \u2014 ${s.optString("name")}",
                                s.optJSONObject("forecastAdvisory")?.optString("url"), false)
                        })
                        box.addView(ctx.actionButton("\uD83D\uDCC4  Forecast discussion") {
                            showText("Forecast discussion \u2014 ${s.optString("name")}",
                                s.optJSONObject("forecastDiscussion")?.optString("url"), true)
                        })
                        box.addView(ctx.actionButton("\uD83D\uDCC4  Wind speed probabilities") {
                            showText("Wind speed probabilities \u2014 ${s.optString("name")}",
                                s.optJSONObject("windSpeedProbabilities")?.optString("url"), false)
                        })
                    }
                    box.addView(ctx.body("Advisories are issued every 6 hours (every 3 hours when watches or warnings are in effect)."))
                    addOutlook()
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
            append("Intensity: ").append(s.optString("intensity").toIntOrNull()?.let { Units.knots(ctx, it) } ?: (s.optString("intensity") + " kt")).append("\n")
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

    // ---------------------------------------------------------------- distance & closest approach

    private fun haversineKm(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(la2 - la1)
        val dLon = Math.toRadians(lo2 - lo1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun bearingDeg(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val p1 = Math.toRadians(la1)
        val p2 = Math.toRadians(la2)
        val dl = Math.toRadians(lo2 - lo1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** "420 mi ENE of your location" plus, once the track loads, the closest forecast position. */
    private fun addDistance(s: JSONObject) {
        val sLat = s.optDouble("latitudeNumeric", Double.NaN)
        val sLon = s.optDouble("longitudeNumeric", Double.NaN)
        if (sLat.isNaN() || sLon.isNaN()) return
        val km = haversineKm(userLat, userLon, sLat, sLon)
        val dir = cardinal(bearingDeg(userLat, userLon, sLat, sLon))
        val base = "\uD83D\uDCCF  ${Units.distanceKm(ctx, km)} $dir of your location"
        val tv = ctx.body(base)
        box.addView(tv)

        val trackUrl = s.optJSONObject("forecastTrack")?.optString("kmzFile")
        scope.launch {
            val lines = ArrayList<String>()
            // Closest forecast position from the track points
            try {
                if (!trackUrl.isNullOrBlank() && trackUrl.startsWith("http")) {
                    val kml = extractKml(Api.getBytes(trackUrl))
                    if (kml != null) {
                        val pts = parseKmlShapes(kml, sLon).filter { it.kind == GeoKind.POINT }
                        var bestKm = km
                        var bestLabel: String? = null
                        for (sh in pts) {
                            val p = sh.pts.firstOrNull() ?: continue
                            val d = haversineKm(userLat, userLon, p.second, p.first)
                            if (d < bestKm) { bestKm = d; bestLabel = sh.label }
                        }
                        when {
                            pts.isEmpty() -> {}
                            bestLabel == null -> lines.add("Forecast track moves away from you (it is closest now).")
                            else -> lines.add(
                                "Closest forecast position: ${Units.distanceKm(ctx, bestKm)} away at $bestLabel " +
                                    "(track points only \u2014 not a landfall forecast)."
                            )
                        }
                    }
                }
            } catch (_: Exception) {}

            // NHC tropical-storm-wind arrival zones that contain your location (if published)
            for ((key, title) in listOf(
                "earliestArrivalTimeTSWindsGIS" to "Earliest reasonable TS-wind arrival",
                "mostLikelyTimeTSWindsGIS" to "Most likely TS-wind arrival"
            )) {
                try {
                    val url = s.optJSONObject(key)?.optString("kmzFile")
                    if (url.isNullOrBlank() || !url.startsWith("http")) continue
                    val kml = extractKml(Api.getBytes(url)) ?: continue
                    val uLon = userLon + 360.0 * Math.round((sLon - userLon) / 360.0)
                    val hits = parseKmlShapes(kml, sLon)
                        .filter { it.kind == GeoKind.POLYGON && it.name.isNotBlank() && Geo.inRing(it.pts, uLon, userLat) }
                        .map { it.name }.distinct()
                    if (hits.isNotEmpty()) lines.add("$title: ${hits.joinToString(" / ")}")
                } catch (_: Exception) {}
            }
            if (lines.isNotEmpty()) tv.text = base + "\n" + lines.joinToString("\n")
        }
    }

    // ---------------------------------------------------------------- tropical weather outlook

    private fun addOutlook() {
        box.addView(ctx.heading("Tropical weather outlook (7 days)"))
        box.addView(ctx.body("Areas NHC is watching for development, with formation chances."))
        addOutlookImage("Atlantic", "https://www.nhc.noaa.gov/xgtwo/two_atl_7d0.png")
        addOutlookImage("Eastern Pacific", "https://www.nhc.noaa.gov/xgtwo/two_pac_7d0.png")
        box.addView(ctx.actionButton("\uD83D\uDCC4  Atlantic outlook text") {
            showText("Atlantic outlook", "https://www.nhc.noaa.gov/text/MIATWOAT.shtml", true)
        })
        box.addView(ctx.actionButton("\uD83D\uDCC4  Eastern Pacific outlook text") {
            showText("Eastern Pacific outlook", "https://www.nhc.noaa.gov/text/MIATWOEP.shtml", true)
        })
        box.addView(ctx.actionButton("\uD83D\uDCC4  Central Pacific outlook text") {
            showText("Central Pacific outlook", "https://www.nhc.noaa.gov/text/HFOTWOCP.shtml", true)
        })
    }

    private fun addOutlookImage(title: String, url: String) {
        val iv = ImageView(ctx).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), dp(6), dp(16), 0) }
        }
        val cap = ctx.body(title).apply { visibility = View.GONE }
        box.addView(cap)
        box.addView(iv)
        scope.launch {
            try {
                val bytes = Api.getBytes(url)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
                iv.setImageBitmap(bmp)
                iv.visibility = View.VISIBLE
                cap.visibility = View.VISIBLE
            } catch (_: Exception) {
                // Graphic unavailable — the text outlook button below still works.
            }
        }
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
                    extractKml(Api.getBytes(u))?.let { shapes.addAll(parseKmlShapes(it, if (lon != 0.0) lon else null)) }
                }
                val wwUrl = s.optJSONObject("windWatchesWarnings")?.optString("kmzFile")
                if (!wwUrl.isNullOrBlank() && wwUrl.startsWith("http")) {
                    try {
                        extractKml(Api.getBytes(wwUrl))?.let {
                            shapes.addAll(parseKmlShapes(it, if (lon != 0.0) lon else null, true))
                        }
                    } catch (_: Exception) {
                        // Watches/warnings are optional extras.
                    }
                }
                if (shapes.isEmpty()) throw IOException("No track data available for $name yet")
                withContext(Dispatchers.Main) {
                    PageActivity.openMap(ctx, "$name \u2014 cone & track", mapHtml(name, shapes, lat, lon))
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

    private class GeoShape(
        val kind: GeoKind,
        val pts: List<Pair<Double, Double>>,
        val label: String,
        val name: String = "",
        val ww: Boolean = false
    )

    private fun parseKmlShapes(kml: String, refLonIn: Double?, ww: Boolean = false): List<GeoShape> {
        var refLon = refLonIn
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
            val pmName = Regex("<name>(.*?)</name>", opts).find(block)?.groupValues?.get(1)
                ?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim().orEmpty()
            for (cm in coordRe.findAll(block)) {
                val pts = ArrayList<Pair<Double, Double>>()
                for (tok in cm.groupValues[1].trim().split(Regex("\\s+"))) {
                    if (tok.isBlank()) continue
                    val c = tok.split(",")
                    if (c.size >= 2) {
                        val lo0 = c[0].toDoubleOrNull() ?: continue
                        val la = c[1].toDoubleOrNull() ?: continue
                        // Keep every point in one continuous longitude frame centred on the storm,
                        // so storms near the dateline don't draw a line across the whole world.
                        if (refLon == null) refLon = lo0
                        val lo = lo0 + 360.0 * Math.round((refLon!! - lo0) / 360.0)
                        pts.add(lo to la)
                    }
                }
                if (pts.isNotEmpty()) shapes.add(GeoShape(kind, pts, label, pmName, ww))
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
                    .put("name", sh.name)
                    .put("ww", sh.ww)
            )
        }
        return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>__LEAFLET_CSS__</style>
        <style>html,body,#map{margin:0;padding:0;height:100%;background:#0B1220}</style>
        <style>#status{position:absolute;top:10px;left:10px;z-index:1000;background:rgba(10,16,30,.88);color:#cbd5e1;padding:6px 12px;border-radius:8px;font:13px sans-serif}</style>
        <script>window.onerror = function(msg) { var s = document.getElementById('status'); if (s) s.textContent = 'Map error: ' + msg; };</script>
        <script>__LEAFLET_JS__</script>
        </head>
        <body>
        <div id="map"></div>
        <div id="status">Loading map...</div>
        <script>
        var data = __DATA__;
        function setStatus(t) {
          var s = document.getElementById('status');
          if (t === null) { s.style.display = 'none'; }
          else { s.style.display = 'block'; s.textContent = t; }
        }
        var map = L.map('map', {minZoom: 2, maxZoom: 16});
        map.whenReady(function() {
          setTimeout(function() {
            if (map.getSize().x > 0) setStatus(null);
            else setStatus('Map area did not size correctly');
          }, 0);
        });
        var tileErrShown = false;
        map.on('tileerror', function(e) {
          if (tileErrShown) return;
          tileErrShown = true;
          var m = (e.tile && e.tile.src || '').match(/tile\/(\d+)\//);
          setStatus('Some basemap tiles failed to load' + (m ? ' (zoom ' + m[1] + ')' : ''));
        });
        L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}', {
          attribution: 'Tiles &copy; Esri &mdash; Esri, HERE, Garmin, FAO, NOAA, USGS',
          maxZoom: 16
        }).addTo(map);
        L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/{z}/{y}/{x}', {
          maxZoom: 16, opacity: 0.9
        }).addTo(map);
        var all = [];
        var points = [];
        data.forEach(function(sh) {
          var ll = sh.pts.map(function(p) { return [p[1], p[0]]; });
          ll.forEach(function(q){ all.push(q); });
          if (sh.ww) {
            var n = (sh.name || '').toLowerCase(), wc = '#A3A3A3';
            if (n.indexOf('hurricane warning') >= 0) wc = '#E11D48';
            else if (n.indexOf('hurricane watch') >= 0) wc = '#F472B6';
            else if (n.indexOf('tropical storm warning') >= 0) wc = '#3B82F6';
            else if (n.indexOf('tropical storm watch') >= 0) wc = '#FACC15';
            L.polyline(ll, {color: wc, weight: 7, opacity: 0.95}).addTo(map)
              .bindTooltip(sh.name || 'Watch / warning', {sticky: true});
            return;
          }
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
                        '<span style="color:#FBBF24">&#9644;</span> Forecast track<br>' +
                        '<span style="color:#E11D48">&#9644;</span> Hurricane warning<br>' +
                        '<span style="color:#F472B6">&#9644;</span> Hurricane watch<br>' +
                        '<span style="color:#3B82F6">&#9644;</span> Tropical storm warning<br>' +
                        '<span style="color:#FACC15">&#9644;</span> Tropical storm watch';
          return d;
        };
        legend.addTo(map);
        if (all.length > 0) { map.fitBounds(L.latLngBounds(all).pad(0.15)); }
        else { map.setView([__LAT__, __LON__], 5); }
        </script>
        </body>
        </html>
        """.trimIndent()
            .replace("__LEAFLET_CSS__", leafletCss)
            .replace("__LEAFLET_JS__", leafletJs)
            .replace("__DATA__", data.toString())
            .replace("__LAT__", fmt(lat))
            .replace("__LON__", fmt(lon))
    }

    // ---------------------------------------------------------------- advisory text

    private fun showText(title: String, url: String?, reflow: Boolean) {
        if (url.isNullOrBlank() || !url.startsWith("http")) {
            Toast.makeText(ctx, "Not available yet", Toast.LENGTH_SHORT).show()
            return
        }
        PageActivity.openText(ctx, title, url, reflow)
    }
}
