package com.example.stormcast

import android.content.Context
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class HourSlot(val label: String, val emoji: String, val temp: String)

data class WidgetData(
    val place: String,
    val temp: String?,
    val feelsLike: String?,
    val description: String,
    val stats: String?,        // e.g. "NE 10 mph  ·  65% humidity"
    val high: String?,
    val low: String?,
    val nextRain: String?,     // null = unknown, hide the row
    val hours: List<HourSlot>,
    val storm: String?,
    val alerts: List<AlertInfo>
)

/** Gathers everything the home-screen widget shows. Every extra is best-effort. */
object WidgetFeed {

    private fun emoji(text: String, day: Boolean): String {
        val s = text.lowercase()
        return when {
            "thunder" in s -> "⛈️"
            "snow" in s || "flurr" in s -> "❄️"
            "sleet" in s || "freezing" in s -> "🌨️"
            "rain" in s || "shower" in s || "drizzle" in s -> "🌧️"
            "fog" in s || "haze" in s || "smoke" in s -> "🌫️"
            "partly" in s || "mostly sunny" in s || "mostly clear" in s -> if (day) "⛅" else "☁️"
            "cloud" in s || "overcast" in s -> "☁️"
            "sunny" in s || "clear" in s -> if (day) "☀️" else "🌙"
            else -> if (day) "🌤️" else "🌙"
        }
    }

    private fun cardinal(deg: Double): String {
        val d = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return d[((deg % 360 + 360) % 360 / 22.5).roundToInt() % 16]
    }

    private fun haversineKm(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val dLat = Math.toRadians(la2 - la1)
        val dLon = Math.toRadians(lo2 - lo1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6371.0 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun bearingDeg(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val p1 = Math.toRadians(la1)
        val p2 = Math.toRadians(la2)
        val dl = Math.toRadians(lo2 - lo1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** "Hurricane Milton · 420 mi ENE" for the closest active storm, or null if none. */
    private suspend fun nearestStorm(ctx: Context, lat: Double, lon: Double): String? {
        val storms = Api.getJson("https://www.nhc.noaa.gov/CurrentStorms.json")
            .optJSONArray("activeStorms") ?: return null
        var best: String? = null
        var bestKm = Double.MAX_VALUE
        for (i in 0 until storms.length()) {
            val s = storms.getJSONObject(i)
            val la = s.optDouble("latitudeNumeric", Double.NaN)
            val lo = s.optDouble("longitudeNumeric", Double.NaN)
            if (la.isNaN() || lo.isNaN()) continue
            val km = haversineKm(lat, lon, la, lo)
            if (km < bestKm) {
                bestKm = km
                val cls = when (s.optString("classification")) {
                    "HU" -> "Hurricane"
                    "TS" -> "Tropical Storm"
                    "TD" -> "Tropical Depression"
                    "STS" -> "Subtropical Storm"
                    "STD" -> "Subtropical Depression"
                    "PTC" -> "Potential Tropical Cyclone"
                    else -> "Storm"
                }
                best = "🌀 $cls ${s.optString("name")} · " +
                    "${Units.distanceKm(ctx, km)} ${cardinal(bearingDeg(lat, lon, la, lo))}"
            }
        }
        return best
    }

    suspend fun load(ctx: Context, lat: Double, lon: Double, large: Boolean): WidgetData {
        val props = try {
            Api.getJson("https://api.weather.gov/points/${fmt(lat)},${fmt(lon)}").getJSONObject("properties")
        } catch (_: Exception) { null }

        val rel = props?.optJSONObject("relativeLocation")?.optJSONObject("properties")
        val place = if (rel != null) "${rel.optString("city")}, ${rel.optString("state")}" else "${fmt(lat)}, ${fmt(lon)}"

        // Current conditions
        val obs = try {
            props?.let { Nws.observation(it.optString("observationStations"))?.first }
        } catch (_: Exception) { null }
        val tempC = obs?.let { Nws.num(it, "temperature") }
        val feelsC = obs?.let { Nws.num(it, "heatIndex") ?: Nws.num(it, "windChill") } ?: tempC
        val feels = feelsC?.let { Units.temp(ctx, it) }
        val wind = obs?.let { Nws.num(it, "windSpeed") }
        val dir = obs?.let { Nws.num(it, "windDirection") }
        val hum = obs?.let { Nws.num(it, "relativeHumidity") }
        val stats = listOfNotNull(
            wind?.let { if (it < 1.5) "Calm" else (dir?.let { d -> cardinal(d) + " " } ?: "") + Units.speedKmh(ctx, it) },
            hum?.let { "${it.roundToInt()}% humidity" }
        ).joinToString("  ·  ").ifBlank { null }

        var high: String? = null
        var low: String? = null
        var nextRain: String? = null
        val hours = ArrayList<HourSlot>()

        if (large && props != null) {
            val q = Units.nwsQuery(ctx)
            try {
                val periods = Api.getJson(props.optString("forecast") + q)
                    .getJSONObject("properties").getJSONArray("periods")
                val p0 = periods.getJSONObject(0)
                val p1 = periods.optJSONObject(1)
                if (p0.optBoolean("isDaytime", true)) {
                    high = "${p0.optString("temperature")}°"
                    low = p1?.let { "${it.optString("temperature")}°" }
                } else {
                    low = "${p0.optString("temperature")}°"
                }
            } catch (_: Exception) {}

            try {
                val h = Api.getJson(props.optString("forecastHourly") + q)
                    .getJSONObject("properties").getJSONArray("periods")
                val hourFmt = DateTimeFormatter.ofPattern("ha", Locale.US)
                val rainFmt = DateTimeFormatter.ofPattern("h a", Locale.US)
                var rain: String? = null
                for (i in 0 until minOf(h.length(), 24)) {
                    val p = h.getJSONObject(i)
                    val start = try { OffsetDateTime.parse(p.optString("startTime")) } catch (_: Exception) { null }
                    if (i < 6 && start != null) {
                        hours.add(
                            HourSlot(
                                start.format(hourFmt),
                                emoji(p.optString("shortForecast"), p.optBoolean("isDaytime", true)),
                                "${p.optString("temperature")}°"
                            )
                        )
                    }
                    val pop = p.optJSONObject("probabilityOfPrecipitation")
                        ?.let { if (it.isNull("value")) 0 else it.optInt("value") } ?: 0
                    if (rain == null && pop >= 40 && start != null) {
                        rain = if (i == 0) "Rain likely now ($pop%)"
                        else "Rain likely around ${start.format(rainFmt)} ($pop%)"
                    }
                }
                nextRain = rain ?: "No rain expected in the next 24 hours"
            } catch (_: Exception) {}
        }

        val storm = if (large) try { nearestStorm(ctx, lat, lon) } catch (_: Exception) { null } else null
        val alerts = try {
            Nws.alerts(lat, lon).sortedByDescending { Nws.severityRank(it.severity) }
        } catch (_: Exception) { emptyList() }

        return WidgetData(
            place = place,
            temp = tempC?.let { Units.temp(ctx, it) },
            feelsLike = feels,
            description = obs?.optString("textDescription").orEmpty(),
            stats = stats,
            high = high,
            low = low,
            nextRain = nextRain,
            hours = hours,
            storm = storm,
            alerts = alerts
        )
    }
}
