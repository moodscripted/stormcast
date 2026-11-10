package com.example.stormcast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HurricanePanel(private val ctx: Context) : Panel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(12))
    }

    override val root: View = ScrollView(ctx).apply {
        fillViewport = true
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
                        box.addView(ctx.body("No active tropical cyclones currently tracked by the National Hurricane Center in any basin."))
                        return@withContext
                    }
                    for (i in 0 until storms.length()) {
                        val s = storms.getJSONObject(i)
                        val cls = when (s.optString("classification")) {
                            "HU" -> "Hurricane"
                            "TS" -> "Tropical Storm"
                            "TD" -> "Tropical Depression"
                            "STD" -> "Subtropical Depression"
                            "STS" -> "Subtropical Storm"
                            "PSTD", "PS" -> "Post-Tropical Cyclone"
                            else -> s.optString("classification")
                        }
                        val text = buildString {
                            append(cls).append(" ").append(s.optString("name"))
                                .append(" (").append(s.optString("id")).append(")\n\n")
                            append("Intensity: ").append(s.optString("intensity")).append("\n")
                            append("Pressure: ").append(s.optString("pressure")).append("\n")
                            append("Position: ").append(s.optString("latitude"))
                                .append(", ").append(s.optString("longitude")).append("\n")
                            append("Movement: ").append(s.optString("movementDir"))
                                .append(" at ").append(s.optString("movementSpeed")).append("\n")
                            append("Updated: ").append(s.optString("lastUpdate"))
                        }
                        box.addView(ctx.card(text, 0xFFD32F2F.toInt()))
                        addLink("Public advisory", s.optJSONObject("publicAdvisory")?.optString("url"))
                        addLink("Forecast advisory", s.optJSONObject("forecastAdvisory")?.optString("url"))
                        addLink("Forecast discussion", s.optJSONObject("forecastDiscussion")?.optString("url"))
                    }
                    box.addView(ctx.body("Track maps and GIS data: https://www.nhc.noaa.gov/"))
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

    private fun addLink(label: String, url: String?) {
        if (url.isNullOrBlank() || !url.startsWith("http")) return
        box.addView(TextView(ctx).apply {
            text = "› $label"
            textSize = 14f
            setTextColor(0xFF1976D2.toInt())
            setPadding(dp(28), dp(6), dp(16), 0)
            setOnClickListener {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        })
    }
}
