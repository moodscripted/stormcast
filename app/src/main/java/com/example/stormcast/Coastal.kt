package com.example.stormcast

import android.content.Context
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

data class Tide(val time: String, val high: Boolean, val height: String)
data class Coastal(val station: String?, val tides: List<Tide>, val waves: String?)
data class AirQuality(
    val aqi: Int?,
    val label: String,
    val pollutants: String?,       // e.g. "PM2.5 8 \u00B7 PM10 14 \u00B7 O\u2083 52 \u00B5g/m\u00B3"
    val pollen: List<String>,      // e.g. "Grass: High (95)", highest first
    val pollenNone: Boolean        // pollen data present but all zero
)

/** Tides (NOAA CO-OPS), waves (Open-Meteo marine) and air quality (Open-Meteo). All best-effort. */
object Extras {

    private const val MAX_STATION_KM = 60.0

    suspend fun airQuality(lat: Double, lon: Double): AirQuality? {
        val vars = "us_aqi,pm2_5,pm10,ozone,nitrogen_dioxide,sulphur_dioxide,carbon_monoxide," +
            "alder_pollen,birch_pollen,grass_pollen,mugwort_pollen,olive_pollen,ragweed_pollen"
        val cur = Api.getJson(
            "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${fmt(lat)}&longitude=${fmt(lon)}&current=$vars"
        ).optJSONObject("current") ?: return null

        fun d(k: String): Double? = if (cur.isNull(k) || !cur.has(k)) null else cur.optDouble(k).takeIf { !it.isNaN() }

        val aqi = d("us_aqi")?.roundToInt()
        val label = when {
            aqi == null -> ""
            aqi <= 50 -> "Good"
            aqi <= 100 -> "Moderate"
            aqi <= 150 -> "Unhealthy for sensitive groups"
            aqi <= 200 -> "Unhealthy"
            aqi <= 300 -> "Very unhealthy"
            else -> "Hazardous"
        }

        val pol = listOfNotNull(
            d("pm2_5")?.let { "PM2.5 ${it.roundToInt()}" },
            d("pm10")?.let { "PM10 ${it.roundToInt()}" },
            d("ozone")?.let { "O\u2083 ${it.roundToInt()}" },
            d("nitrogen_dioxide")?.let { "NO\u2082 ${it.roundToInt()}" },
            d("sulphur_dioxide")?.let { "SO\u2082 ${it.roundToInt()}" }
        )
        val pollutants = if (pol.isEmpty()) null else pol.joinToString("  \u00B7  ") + "  \u00B5g/m\u00B3"

        // Pollen in grains/m\u00B3. Level cut-offs are a rough guide, not an official scale.
        val names = listOf(
            "alder_pollen" to "Alder", "birch_pollen" to "Birch", "grass_pollen" to "Grass",
            "mugwort_pollen" to "Mugwort", "olive_pollen" to "Olive", "ragweed_pollen" to "Ragweed"
        )
        val present = names.mapNotNull { (k, n) -> d(k)?.let { n to it } }
        fun level(v: Double) = when {
            v < 10 -> "Low"
            v < 50 -> "Moderate"
            v < 200 -> "High"
            else -> "Very high"
        }
        val pollen = present.filter { it.second >= 1.0 }
            .sortedByDescending { it.second }
            .map { "${it.first}: ${level(it.second)} (${it.second.roundToInt()})" }

        if (aqi == null && pollutants == null && present.isEmpty()) return null
        return AirQuality(aqi, label, pollutants, pollen, present.isNotEmpty() && pollen.isEmpty())
    }

    suspend fun coastal(ctx: Context, lat: Double, lon: Double): Coastal? {
        val metric = Units.metric(ctx)

        // Waves: Open-Meteo returns nulls for inland points.
        val waves = try {
            val cur = Api.getJson(
                "https://marine-api.open-meteo.com/v1/marine?latitude=${fmt(lat)}&longitude=${fmt(lon)}" +
                    "&current=wave_height,wave_period,wave_direction"
            ).optJSONObject("current")
            if (cur == null || cur.isNull("wave_height")) null else {
                val m = cur.optDouble("wave_height")
                val h = if (metric) String.format(Locale.US, "%.1f m", m)
                else String.format(Locale.US, "%.1f ft", m * 3.28084)
                val per = if (cur.isNull("wave_period")) "" else " at ${cur.optDouble("wave_period").roundToInt()} s"
                val dir = if (cur.isNull("wave_direction")) "" else " from ${Geo.cardinal(cur.optDouble("wave_direction"))}"
                "Waves $h$per$dir"
            }
        } catch (_: Exception) { null }

        // Tides from the nearest NOAA prediction station within range.
        var station: String? = null
        val tides = ArrayList<Tide>()
        try {
            val list = Api.getJson(
                "https://api.tidesandcurrents.noaa.gov/mdapi/prod/webapi/stations.json?type=tidepredictions"
            ).optJSONArray("stations")
            var bestId: String? = null
            var bestName = ""
            var bestKm = Double.MAX_VALUE
            if (list != null) {
                for (i in 0 until list.length()) {
                    val s = list.getJSONObject(i)
                    val km = Geo.haversineKm(lat, lon, s.optDouble("lat"), s.optDouble("lng"))
                    if (km < bestKm) { bestKm = km; bestId = s.optString("id"); bestName = s.optString("name") }
                }
            }
            if (bestId != null && bestKm <= MAX_STATION_KM) {
                val begin = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                val unit = if (metric) "metric" else "english"
                val preds = Api.getJson(
                    "https://api.tidesandcurrents.noaa.gov/api/prod/datagetter?product=predictions" +
                        "&application=StormCast&begin_date=$begin&range=48&datum=MLLW&station=$bestId" +
                        "&time_zone=lst_ldt&interval=hilo&units=$unit&format=json"
                ).optJSONArray("predictions")
                val parse = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
                val show = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)
                val now = LocalDateTime.now()
                if (preds != null) {
                    for (i in 0 until preds.length()) {
                        if (tides.size >= 4) break
                        val p = preds.getJSONObject(i)
                        val t = try { LocalDateTime.parse(p.optString("t"), parse) } catch (_: Exception) { continue }
                        if (t.isBefore(now)) continue
                        val v = p.optString("v").toDoubleOrNull() ?: continue
                        tides.add(
                            Tide(
                                t.format(show), p.optString("type") == "H",
                                String.format(Locale.US, if (metric) "%.1f m" else "%.1f ft", v)
                            )
                        )
                    }
                }
                if (tides.isNotEmpty()) station = bestName
            }
        } catch (_: Exception) {}

        return if (waves == null && tides.isEmpty()) null else Coastal(station, tides, waves)
    }
}
