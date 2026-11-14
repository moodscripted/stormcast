package com.example.stormcast

data class SpcRisk(val rank: Int, val label: String, val tornadoPct: Int?)

/** NOAA Storm Prediction Center Day 1 convective outlook at a point. */
object Spc {

    private val CATS = listOf("TSTM", "MRGL", "SLGT", "ENH", "MDT", "HIGH")
    private val NAMES = listOf(
        "General thunderstorms (1 of 6)", "Marginal (1 of 5)", "Slight (2 of 5)",
        "Enhanced (3 of 5)", "Moderate (4 of 5)", "High (5 of 5)"
    )

    /** rank = -1 means no thunderstorm risk at the point; null means the lookup failed. */
    suspend fun riskAt(lat: Double, lon: Double): SpcRisk? {
        val cat = try {
            Api.getJson("https://www.spc.noaa.gov/products/outlook/day1otlk_cat.nolyr.geojson")
                .optJSONArray("features")
        } catch (_: Exception) { null } ?: return null

        var rank = -1
        for (i in 0 until cat.length()) {
            val f = cat.getJSONObject(i)
            val r = CATS.indexOf(f.optJSONObject("properties")?.optString("LABEL").orEmpty())
            if (r > rank && Geo.inGeometry(f.optJSONObject("geometry"), lon, lat)) rank = r
        }

        // Tornado probability (best effort): LABEL is a fraction like "0.05".
        var tor: Int? = null
        try {
            val t = Api.getJson("https://www.spc.noaa.gov/products/outlook/day1otlk_torn.nolyr.geojson")
                .optJSONArray("features")
            if (t != null) {
                var best = 0
                for (i in 0 until t.length()) {
                    val f = t.getJSONObject(i)
                    val pct = ((f.optJSONObject("properties")?.optString("LABEL")?.toDoubleOrNull() ?: 0.0) * 100).toInt()
                    if (pct > best && Geo.inGeometry(f.optJSONObject("geometry"), lon, lat)) best = pct
                }
                if (best > 0) tor = best
            }
        } catch (_: Exception) {}

        return SpcRisk(rank, if (rank >= 0) NAMES[rank] else "No severe risk", tor)
    }
}
