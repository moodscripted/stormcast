package com.example.stormcast

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class HurricanePanel(private val ctx: Context) : Panel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private fun dp(v: Int) = ctx.dp(v)

    private val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(12))
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
                        addLink("🗺  Maps, cone & track", s.optJSONObject("forecastGraphics")?.optString("url"))
                        addLink("📄  Public advisory", s.optJSONObject("publicAdvisory")?.optString("url"))
                        addLink("📄  Forecast advisory", s.optJSONObject("forecastAdvisory")?.optString("url"))
                        addLink("📄  Forecast discussion", s.optJSONObject("forecastDiscussion")?.optString("url"))
                        addLink("📄  Wind speed probabilities", s.optJSONObject("windSpeedProbabilities")?.optString("url"))
                        addLink("📍  GIS track (KMZ)", s.optJSONObject("forecastTrack")?.optString("kmzFile"))
                        addLink("📍  GIS cone (KMZ)", s.optJSONObject("trackCone")?.optString("kmzFile"))
                        addLink("📍  GIS wind radii (KMZ)", s.optJSONObject("forecastWindRadiiGIS")?.optString("kmzFile"))
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

    private fun addLink(label: String, url: String?) {
        if (url.isNullOrBlank() || !url.startsWith("http")) return
        val btn = Button(ctx).apply {
            text = label
            isAllCaps = false
            setOnClickListener { showInApp(label, url) }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(dp(28), dp(4), dp(16), 0)
        btn.layoutParams = lp
        box.addView(btn)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showInApp(title: String, url: String) {
        val web = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            loadUrl(url)
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(web)
            .setPositiveButton("Close", null)
            .show()
    }
}
