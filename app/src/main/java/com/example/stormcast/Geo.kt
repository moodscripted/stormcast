package com.example.stormcast

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Small geometry helpers shared by the SPC outlook, tides and cyclone arrival zones. */
object Geo {

    fun haversineKm(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val dLat = Math.toRadians(la2 - la1)
        val dLon = Math.toRadians(lo2 - lo1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6371.0 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun cardinal(deg: Double): String {
        val d = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return d[((deg % 360 + 360) % 360 / 22.5).roundToInt() % 16]
    }

    /** Ray-casting point-in-ring test. Ring points are (lon, lat) pairs. */
    fun inRing(ring: List<Pair<Double, Double>>, lon: Double, lat: Double): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val (xi, yi) = ring[i]
            val (xj, yj) = ring[j]
            if ((yi > lat) != (yj > lat) &&
                lon < (xj - xi) * (lat - yi) / (yj - yi) + xi
            ) inside = !inside
            j = i
        }
        return inside
    }

    private fun ringOf(a: JSONArray): List<Pair<Double, Double>> =
        (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } }

    /** GeoJSON Polygon coordinates: first ring is the outline, the rest are holes. */
    private fun inPolygon(rings: JSONArray, lon: Double, lat: Double): Boolean {
        if (rings.length() == 0 || !inRing(ringOf(rings.getJSONArray(0)), lon, lat)) return false
        for (i in 1 until rings.length()) {
            if (inRing(ringOf(rings.getJSONArray(i)), lon, lat)) return false
        }
        return true
    }

    /** True if (lon, lat) lies inside a GeoJSON Polygon or MultiPolygon geometry. */
    fun inGeometry(geom: JSONObject?, lon: Double, lat: Double): Boolean {
        if (geom == null) return false
        val coords = geom.optJSONArray("coordinates") ?: return false
        return when (geom.optString("type")) {
            "Polygon" -> inPolygon(coords, lon, lat)
            "MultiPolygon" -> (0 until coords.length()).any { inPolygon(coords.getJSONArray(it), lon, lat) }
            else -> false
        }
    }
}
