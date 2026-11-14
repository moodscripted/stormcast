package com.example.stormcast

import org.json.JSONObject

data class AlertInfo(
    val id: String,
    val event: String,
    val severity: String,
    val headline: String,
    val area: String
)

data class Conditions(val tempC: Double?, val description: String, val place: String)

/** Small shared NWS helpers used by the widget and the background alert worker. */
object Nws {

    fun num(o: JSONObject, key: String): Double? {
        val v = o.optJSONObject(key) ?: return null
        return if (v.isNull("value")) null else v.optDouble("value").takeIf { !it.isNaN() }
    }

    /** Latest observation from the nearest stations that is actually reporting a temperature. */
    suspend fun observation(stationsUrl: String): Pair<JSONObject, String>? {
        if (stationsUrl.isBlank()) return null
        val stations = Api.getJson(stationsUrl).optJSONArray("features") ?: return null
        for (i in 0 until minOf(stations.length(), 4)) {
            val pr = stations.getJSONObject(i).getJSONObject("properties")
            val id = pr.optString("stationIdentifier")
            if (id.isBlank()) continue
            try {
                val o = Api.getJson("https://api.weather.gov/stations/$id/observations/latest")
                    .getJSONObject("properties")
                if (num(o, "temperature") != null) return o to "$id · ${pr.optString("name")}"
            } catch (_: Exception) {
            }
        }
        return null
    }

    suspend fun conditions(lat: Double, lon: Double): Conditions? {
        val props = Api.getJson("https://api.weather.gov/points/${fmt(lat)},${fmt(lon)}")
            .getJSONObject("properties")
        val rel = props.optJSONObject("relativeLocation")?.optJSONObject("properties")
        val place = if (rel != null) "${rel.optString("city")}, ${rel.optString("state")}" else ""
        val obs = observation(props.optString("observationStations"))?.first ?: return null
        return Conditions(num(obs, "temperature"), obs.optString("textDescription"), place)
    }

    suspend fun alerts(lat: Double, lon: Double, allowStale: Boolean = true): List<AlertInfo> {
        val data = Api.getJson(
            "https://api.weather.gov/alerts/active?point=${fmt(lat)},${fmt(lon)}", allowStale
        )
        val f = data.getJSONArray("features")
        return (0 until f.length()).map {
            val o = f.getJSONObject(it)
            val p = o.getJSONObject("properties")
            AlertInfo(
                id = o.optString("id").ifBlank { p.optString("id") },
                event = p.optString("event"),
                severity = p.optString("severity"),
                headline = p.optString("headline"),
                area = p.optString("areaDesc")
            )
        }
    }

    fun severityRank(s: String): Int = when (s) {
        "Extreme" -> 4
        "Severe" -> 3
        "Moderate" -> 2
        "Minor" -> 1
        else -> 0
    }
}
