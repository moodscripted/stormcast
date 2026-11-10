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
import java.io.IOException

class ForecastPanel(private val ctx: Context) : Panel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private fun dp(v: Int) = ctx.dp(v)

    private val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(16))
    }

    override val root: View = ScrollView(ctx).apply {
        setFillViewport(true)
        addView(box)
    }

    override fun refresh(lat: Double, lon: Double) {
        job?.cancel()
        box.removeAllViews()
        box.addView(ctx.body("Loading forecast…"))
        job = scope.launch {
            try {
                val points = Api.getJson("https://api.weather.gov/points/${fmt(lat)},${fmt(lon)}")
                val props = points.getJSONObject("properties")
                val forecastUrl = props.optString("forecast").ifBlank { props.optString("forecastUrl") }
                if (forecastUrl.isBlank()) throw IOException("No forecast URL in NWS response")
                val forecast = Api.getJson(forecastUrl)
                val periods = forecast.getJSONObject("properties").getJSONArray("periods")
                withContext(Dispatchers.Main) {
                    box.removeAllViews()
                    box.addView(ctx.heading("NWS forecast"))
                    for (i in 0 until periods.length()) {
                        val p = periods.getJSONObject(i)
                        box.addView(
                            ctx.forecastCard(
                                name = p.optString("name"),
                                temp = p.optString("temperature"),
                                unit = p.optString("temperatureUnit"),
                                short = p.optString("shortForecast"),
                                wind = "Wind: ${p.optString("windSpeed")} ${p.optString("windDirection")}",
                                detail = p.optString("detailedForecast")
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    box.removeAllViews()
                    box.addView(ctx.heading("Forecast unavailable"))
                    box.addView(ctx.body(e.message ?: "Network error"))
                }
            }
        }
    }
}
