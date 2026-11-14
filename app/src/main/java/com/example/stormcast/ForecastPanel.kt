package com.example.stormcast

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

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

    // Loaded data, kept so the 7-Day / Hourly toggle doesn't refetch.
    private var daily: JSONArray? = null
    private var hourly: JSONArray? = null
    private var obs: JSONObject? = null
    private var obsSource: String = ""
    private var extras: JSONObject? = null
    private var severe: SpcRisk? = null
    private var coastal: Coastal? = null
    private var aqi: AirQuality? = null
    private var staleNote: String? = null
    private var place: String = ""
    private var mode = 0 // 0 = 7-day, 1 = hourly
    private lateinit var listBox: LinearLayout

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
                val hourlyUrl = props.optString("forecastHourly")
                val stationsUrl = props.optString("observationStations")
                val rel = props.optJSONObject("relativeLocation")?.optJSONObject("properties")
                val placeName = if (rel != null) "${rel.optString("city")}, ${rel.optString("state")}" else ""

                // Daily is required; hourly and current conditions are best-effort.
                val q = Units.nwsQuery(ctx)
                val (d, h, o) = coroutineScope {
                    val dd = async { Api.getJson(forecastUrl + q).getJSONObject("properties").getJSONArray("periods") }
                    val hh = async {
                        runCatching {
                            if (hourlyUrl.isBlank()) null
                            else Api.getJson(hourlyUrl + q).getJSONObject("properties").getJSONArray("periods")
                        }.getOrNull()
                    }
                    val oo = async { runCatching { Nws.observation(stationsUrl) }.getOrNull() }
                    val ee = async {
                        runCatching {
                            Api.getJson(
                                "https://api.open-meteo.com/v1/forecast?latitude=${fmt(lat)}&longitude=${fmt(lon)}" +
                                    "&daily=sunrise,sunset,uv_index_max&timezone=auto&forecast_days=1"
                            )
                        }.getOrNull()
                    }
                    val sv = async { runCatching { Spc.riskAt(lat, lon) }.getOrNull() }
                    val co = async { runCatching { Extras.coastal(ctx, lat, lon) }.getOrNull() }
                    val aq = async { runCatching { Extras.airQuality(lat, lon) }.getOrNull() }
                    extras = ee.await()
                    severe = sv.await()
                    coastal = co.await()
                    aqi = aq.await()
                    Triple(dd.await(), hh.await(), oo.await())
                }
                withContext(Dispatchers.Main) {
                    daily = d
                    hourly = h
                    obs = o?.first
                    obsSource = o?.second ?: ""
                    place = placeName
                    staleNote = Api.takeStaleNote()
                    render()
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

    // ------------------------------------------------------------------ data

    private fun num(o: JSONObject, key: String): Double? = Nws.num(o, key)

    private fun cardinal(deg: Double): String {
        val dirs = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return dirs[((deg % 360 + 360) % 360 / 22.5).roundToInt() % 16]
    }

    private fun iconFor(text: String, day: Boolean = true): String {
        val s = text.lowercase()
        return when {
            "thunder" in s -> "⛈️"
            "snow" in s || "flurr" in s || "blizzard" in s -> "❄️"
            "sleet" in s || "freezing" in s -> "🌨️"
            "rain" in s || "shower" in s || "drizzle" in s -> "🌧️"
            "fog" in s || "haze" in s || "smoke" in s || "mist" in s -> "🌫️"
            "windy" in s || "breezy" in s -> "💨"
            "partly" in s || "mostly sunny" in s || "mostly clear" in s -> if (day) "⛅" else "☁️"
            "cloud" in s || "overcast" in s -> "☁️"
            "sunny" in s || "clear" in s || "fair" in s -> if (day) "☀️" else "🌙"
            else -> if (day) "🌤️" else "🌙"
        }
    }

    // ------------------------------------------------------------------ UI

    private fun render() {
        box.removeAllViews()
        staleNote?.let { box.addView(ctx.body("\uD83D\uDCE1  $it")) }
        obs?.let {
            box.addView(ctx.heading(if (place.isBlank()) "Current conditions" else "Now \u00B7 $place"))
            box.addView(currentCard(it))
        }
        extrasCard()?.let { box.addView(it) }
        rainCard()?.let { box.addView(it) }
        severeCard()?.let { box.addView(it) }
        airCoastCard()?.let { box.addView(it) }

        box.addView(ctx.heading("Forecast"))
        val (row, tabs) = ctx.tabRow(listOf("7-Day", "Hourly"))
        // tabRow has a larger top margin meant for the main tabs; tighten it here.
        (row.layoutParams as LinearLayout.LayoutParams).setMargins(dp(16), 0, dp(16), dp(2))
        fun style() { tabs.forEachIndexed { i, t -> ctx.styleTab(t, i == mode) } }
        tabs.forEachIndexed { i, t ->
            t.setOnClickListener { mode = i; style(); renderList() }
        }
        style()
        box.addView(row)

        listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(listBox)
        renderList()
    }

    /** NOAA SPC Day 1 convective outlook category (and tornado chance) at this location. */
    private fun severeCard(): View? {
        val r = severe ?: return null
        val color = when (r.rank) {
            -1, 0 -> 0xFF4CAF50.toInt()
            1 -> 0xFF66BB6A.toInt()
            2 -> 0xFFFDD835.toInt()
            3 -> 0xFFFB8C00.toInt()
            4 -> 0xFFE53935.toInt()
            else -> 0xFFD81B60.toInt()
        }
        val card = surfaceBox().apply { setPadding(dp(18), dp(14), dp(18), dp(14)) }
        card.addView(tv("\u26C8\uFE0F  SEVERE STORM RISK TODAY", 10.5f, ctx.cDim()))
        card.addView(tv(r.label, 18f, color, true).apply { setPadding(0, dp(4), 0, 0) })
        if (r.tornadoPct != null) {
            card.addView(tv("Tornado chance within 25 mi: ${r.tornadoPct}%", 13.5f, ctx.cText()).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }
        card.addView(tv("NOAA Storm Prediction Center \u00B7 Day 1 outlook", 11.5f, ctx.cDim()).apply {
            setPadding(0, dp(6), 0, 0)
        })
        return card
    }

    /** Air quality, tides and waves; only shown when data exists for this location. */
    private fun airCoastCard(): View? {
        val a = aqi
        val c = coastal
        if (a == null && c == null) return null
        val card = surfaceBox().apply { setPadding(dp(18), dp(14), dp(18), dp(14)) }
        if (a != null) {
            card.addView(tv("\uD83C\uDF2C\uFE0F  AIR QUALITY & POLLEN", 10.5f, ctx.cDim()))
            if (a.aqi != null) {
                val col = when {
                    a.aqi <= 50 -> 0xFF4CAF50.toInt()
                    a.aqi <= 100 -> 0xFFFDD835.toInt()
                    a.aqi <= 150 -> 0xFFFB8C00.toInt()
                    else -> 0xFFE53935.toInt()
                }
                card.addView(tv("AQI ${a.aqi} \u00B7 ${a.label}", 16f, col, true).apply { setPadding(0, dp(4), 0, 0) })
            }
            a.pollutants?.let {
                card.addView(tv(it, 12.5f, ctx.cDim()).apply { setPadding(0, dp(4), 0, 0) })
            }
            if (a.pollen.isNotEmpty()) {
                card.addView(tv("\uD83C\uDF3C  Pollen (grains/m\u00B3)", 13f, ctx.cDim()).apply { setPadding(0, dp(10), 0, 0) })
                a.pollen.forEach { card.addView(tv(it, 14.5f, ctx.cText(), true).apply { setPadding(0, dp(2), 0, 0) }) }
            } else if (a.pollenNone) {
                card.addView(tv("\uD83C\uDF3C  No pollen detected right now", 13.5f, ctx.cDim()).apply { setPadding(0, dp(10), 0, 0) })
            }
        }
        if (c != null) {
            card.addView(tv("\uD83C\uDF0A  COASTAL", 10.5f, ctx.cDim()).apply {
                setPadding(0, if (a != null) dp(14) else 0, 0, 0)
            })
            c.waves?.let { card.addView(tv(it, 15f, ctx.cText(), true).apply { setPadding(0, dp(4), 0, 0) }) }
            if (c.tides.isNotEmpty()) {
                c.tides.forEach {
                    card.addView(tv("${if (it.high) "\u25B2 High" else "\u25BC Low"}  ${it.time}  \u00B7  ${it.height}",
                        14f, ctx.cText()).apply { setPadding(0, dp(4), 0, 0) })
                }
                c.station?.let { card.addView(tv("Tide station: $it", 11.5f, ctx.cDim()).apply { setPadding(0, dp(6), 0, 0) }) }
            }
        }
        return card
    }

    /** Sunrise / sunset / UV index from Open-Meteo (no key needed). */
    private fun extrasCard(): View? {
        val d = extras?.optJSONObject("daily") ?: return null
        fun first(key: String): String? = d.optJSONArray(key)?.optString(0)?.takeIf { it.isNotBlank() && it != "null" }
        val fmtT = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        fun time(iso: String?): String = try {
            java.time.LocalDateTime.parse(iso).format(fmtT)
        } catch (_: Exception) { "--" }
        val uv = d.optJSONArray("uv_index_max")?.let { if (it.isNull(0)) null else it.optDouble(0) }
        val uvLabel = when {
            uv == null -> "--"
            uv < 3 -> "${uv.roundToInt()} \u00B7 Low"
            uv < 6 -> "${uv.roundToInt()} \u00B7 Moderate"
            uv < 8 -> "${uv.roundToInt()} \u00B7 High"
            uv < 11 -> "${uv.roundToInt()} \u00B7 Very high"
            else -> "${uv.roundToInt()} \u00B7 Extreme"
        }
        val card = surfaceBox().apply { setPadding(dp(18), dp(14), dp(18), dp(14)) }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((k, v) in listOf(
            "\u2600\uFE0F Sunrise" to time(first("sunrise")),
            "\uD83C\uDF19 Sunset" to time(first("sunset")),
            "UV index (max)" to uvLabel
        )) {
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv(k.uppercase(Locale.US), 10.5f, ctx.cDim()))
                addView(tv(v, 14.5f, ctx.cText(), true))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        card.addView(row)
        return card
    }

    /** "Chance of rain" bars for the next 24 hours plus a one-line "next rain" summary. */
    private fun rainCard(): View? {
        val h = hourly ?: return null
        val values = ArrayList<Int>()
        val labels = ArrayList<String>()
        val hourFmt = DateTimeFormatter.ofPattern("ha", Locale.US)
        var nextRain: String? = null
        var maxPop = 0
        for (i in 0 until minOf(h.length(), 24)) {
            val p = h.getJSONObject(i)
            val pop = p.optJSONObject("probabilityOfPrecipitation")
                ?.let { if (it.isNull("value")) 0 else it.optInt("value") } ?: 0
            val start = try { OffsetDateTime.parse(p.optString("startTime")) } catch (_: Exception) { null }
            values.add(pop)
            labels.add(if (i % 3 == 0 && start != null) start.format(hourFmt) else "")
            if (pop > maxPop) maxPop = pop
            if (nextRain == null && pop >= 40 && start != null) {
                nextRain = if (i == 0) "Rain likely now ($pop%)"
                else "Rain likely around ${start.format(DateTimeFormatter.ofPattern("h a", Locale.US))} ($pop%)"
            }
        }
        if (values.isEmpty()) return null
        val summary = nextRain
            ?: if (maxPop >= 20) "Low chance of rain in the next 24 hours (peak $maxPop%)"
            else "No rain expected in the next 24 hours"
        val card = surfaceBox().apply { setPadding(dp(18), dp(14), dp(18), dp(12)) }
        card.addView(tv("\uD83D\uDCA7  $summary", 14.5f, ctx.cText(), true))
        card.addView(tv("CHANCE OF RAIN \u00B7 NEXT 24 H", 10.5f, ctx.cDim()).apply { setPadding(0, dp(4), 0, dp(8)) })
        card.addView(BarChartView(ctx, values, labels, ctx.cPrimary(), ctx.cDim(), 0x338090A8))
        return card
    }

    private fun renderList() {
        listBox.removeAllViews()
        if (mode == 1) {
            val h = hourly
            if (h == null || h.length() == 0) {
                listBox.addView(ctx.body("Hourly forecast isn't available for this location right now."))
            } else {
                renderHourly(h)
            }
        } else {
            val d = daily ?: return
            for (i in 0 until d.length()) {
                val p = d.getJSONObject(i)
                listBox.addView(
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
    }

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun surfaceBox(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(ctx.cSurface())
            cornerRadius = dp(16).toFloat()
        }
        elevation = if (ctx.isNight()) 0f else dp(1).toFloat()
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(16), dp(10), dp(16), 0) }
    }

    private fun currentCard(o: JSONObject): View {
        val card = surfaceBox().apply { setPadding(dp(18), dp(16), dp(18), dp(14)) }
        val desc = o.optString("textDescription")
        val tempC = num(o, "temperature")
        val feelsC = num(o, "heatIndex") ?: num(o, "windChill") ?: tempC

        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(tv(iconFor(desc), 44f, ctx.cText()))
        top.addView(tv(tempC?.let { Units.temp(ctx, it) } ?: "--", 52f, ctx.cText(), true).apply {
            setPadding(dp(12), 0, dp(12), 0)
        })
        val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        right.addView(tv(desc.ifBlank { "Conditions" }, 16f, ctx.cText(), true))
        if (feelsC != null && tempC != null && Math.abs(feelsC - tempC) >= 0.6) {
            right.addView(tv("Feels like ${Units.temp(ctx, feelsC)}", 14f, ctx.cDim()))
        }
        top.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(top)

        val windKmh = num(o, "windSpeed")
        val gustKmh = num(o, "windGust")
        val windDir = num(o, "windDirection")
        val windTxt = when {
            windKmh == null -> "--"
            windKmh < 1.5 -> "Calm"
            else -> (windDir?.let { cardinal(it) + " " } ?: "") + Units.speedKmh(ctx, windKmh) +
                (gustKmh?.let { " (gust ${Units.speedKmh(ctx, it).substringBefore(' ')})" } ?: "")
        }
        val stats = listOf(
            "Humidity" to (num(o, "relativeHumidity")?.let { "${it.roundToInt()}%" } ?: "--"),
            "Wind" to windTxt,
            "Dew point" to (num(o, "dewpoint")?.let { Units.temp(ctx, it) } ?: "--"),
            "Pressure" to (num(o, "barometricPressure")?.let { Units.pressurePa(ctx, it) } ?: "--"),
            "Visibility" to (num(o, "visibility")?.let { Units.visibilityM(ctx, it) } ?: "--"),
            "Updated" to prettyTime(o.optString("timestamp"))
        )
        var i = 0
        while (i < stats.size) {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in 0..1) {
                val (k, v) = stats[i + j]
                val cell = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(tv(k.uppercase(Locale.US), 11f, ctx.cDim()))
                    addView(tv(v, 15f, ctx.cText(), true))
                }
                r.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            card.addView(r, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) })
            i += 2
        }
        if (obsSource.isNotBlank()) {
            card.addView(tv("Station $obsSource", 11.5f, ctx.cDim()).apply { setPadding(0, dp(14), 0, 0) })
        }
        return card
    }

    private fun prettyTime(iso: String): String = try {
        OffsetDateTime.parse(iso).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    } catch (_: Exception) {
        "--"
    }

    private fun renderHourly(h: JSONArray) {
        val dayFmt = DateTimeFormatter.ofPattern("EEEE", Locale.US)
        val hourFmt = DateTimeFormatter.ofPattern("h a", Locale.US)
        var lastDate: java.time.LocalDate? = null
        var firstDate: java.time.LocalDate? = null
        var card: LinearLayout? = null
        val count = minOf(h.length(), 48)
        for (i in 0 until count) {
            val p = h.getJSONObject(i)
            val start = try { OffsetDateTime.parse(p.optString("startTime")) } catch (_: Exception) { continue }
            val date = start.toLocalDate()
            if (firstDate == null) firstDate = date
            if (date != lastDate) {
                lastDate = date
                val label = if (date == firstDate) "Today" else start.format(dayFmt)
                listBox.addView(tv(label.uppercase(Locale.US), 12f, ctx.cDim(), true).apply {
                    setPadding(dp(22), dp(16), dp(20), dp(0))
                    letterSpacing = 0.06f
                })
                card = surfaceBox()
                listBox.addView(card)
            }
            val c = card ?: continue
            if (c.childCount > 0) {
                c.addView(View(ctx).apply { setBackgroundColor(0x1F8090A8) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            }
            c.addView(hourRow(p, start.format(hourFmt)))
        }
    }

    private fun hourRow(p: JSONObject, time: String): View {
        val short = p.optString("shortForecast")
        val day = p.optBoolean("isDaytime", true)
        val pop = p.optJSONObject("probabilityOfPrecipitation")
            ?.let { if (it.isNull("value")) null else it.optInt("value") } ?: 0
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(tv(time, 14f, ctx.cDim()), LinearLayout.LayoutParams(dp(50), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(tv(iconFor(short, day), 20f, ctx.cText()), LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(tv("${p.optString("temperature")}°", 17f, ctx.cText(), true),
                LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.WRAP_CONTENT))
            val mid = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv(short, 14f, ctx.cText()))
                addView(tv("${p.optString("windSpeed")} ${p.optString("windDirection")}", 12f, ctx.cDim()))
            }
            addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (pop > 0) addView(tv("💧 $pop%", 13f, ctx.cPrimary(), true))
        }
    }
}
