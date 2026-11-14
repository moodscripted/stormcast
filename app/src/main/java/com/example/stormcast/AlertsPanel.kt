package com.example.stormcast

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlertsPanel(private val ctx: Context) : Panel {

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
        box.addView(ctx.body("Checking active alerts\u2026"))
        job = scope.launch {
            try {
                val url = "https://api.weather.gov/alerts/active?point=${fmt(lat)},${fmt(lon)}"
                val data = Api.getJson(url)
                val features = data.getJSONArray("features")
                withContext(Dispatchers.Main) {
                    box.removeAllViews()
                    Api.takeStaleNote()?.let { box.addView(ctx.body("\uD83D\uDCE1  $it")) }
                    box.addView(ctx.heading("Active alerts (${features.length()})"))
                    if (features.length() == 0) {
                        box.addView(ctx.body("No active alerts for this location."))
                    }
                    for (i in 0 until features.length()) {
                        val p = features.getJSONObject(i).getJSONObject("properties")
                        val sev = p.optString("severity")
                        val accent = when (sev) {
                            "Extreme" -> 0xFFD32F2F.toInt()
                            "Severe" -> 0xFFF57C00.toInt()
                            "Moderate" -> 0xFFFBC02D.toInt()
                            else -> 0xFF1976D2.toInt()
                        }
                        val text = buildString {
                            append(p.optString("event")).append("  [").append(sev).append("]\n\n")
                            p.optString("headline").takeIf { it.isNotBlank() }
                                ?.let { append(it).append("\n\n") }
                            append("Areas: ").append(p.optString("areaDesc")).append("\n")
                            append("Effective: ").append(p.optString("effective")).append("\n")
                            append("Expires: ").append(p.optString("expires")).append("\n\n")
                            append(p.optString("description"))
                            p.optString("instruction").takeIf { it.isNotBlank() }
                                ?.let { append("\n\nInstructions:\n").append(it) }
                        }
                        box.addView(ctx.card(text, accent))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    box.removeAllViews()
                    box.addView(ctx.heading("Alerts unavailable"))
                    box.addView(ctx.body(e.message ?: "Network error"))
                }
            }
        }
    }
}
