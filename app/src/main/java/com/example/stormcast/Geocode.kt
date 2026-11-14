package com.example.stormcast

import org.json.JSONArray
import java.net.URLEncoder

/** City/state or ZIP -> coordinates (US only, matching the NWS/NHC coverage). */
object Geocode {

    private val STATES = mapOf(
        "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas",
        "CA" to "California", "CO" to "Colorado", "CT" to "Connecticut", "DE" to "Delaware",
        "DC" to "District of Columbia", "FL" to "Florida", "GA" to "Georgia", "HI" to "Hawaii",
        "ID" to "Idaho", "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas",
        "KY" to "Kentucky", "LA" to "Louisiana", "ME" to "Maine", "MD" to "Maryland",
        "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota", "MS" to "Mississippi",
        "MO" to "Missouri", "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada",
        "NH" to "New Hampshire", "NJ" to "New Jersey", "NM" to "New Mexico", "NY" to "New York",
        "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio", "OK" to "Oklahoma",
        "OR" to "Oregon", "PA" to "Pennsylvania", "RI" to "Rhode Island", "SC" to "South Carolina",
        "SD" to "South Dakota", "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah",
        "VT" to "Vermont", "VA" to "Virginia", "WA" to "Washington", "WV" to "West Virginia",
        "WI" to "Wisconsin", "WY" to "Wyoming", "PR" to "Puerto Rico", "GU" to "Guam",
        "VI" to "U.S. Virgin Islands", "AS" to "American Samoa", "MP" to "Northern Mariana Islands"
    )

    private val COORDS = Regex("""^\s*(-?\d{1,3}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")

    private fun abbr(state: String): String =
        STATES.entries.firstOrNull { it.value.equals(state, ignoreCase = true) }?.key ?: state

    /** Throws on network failure; returns an empty list when nothing matched. */
    suspend fun search(raw: String): List<Place> {
        val q = raw.trim()
        COORDS.find(q)?.let {
            val la = it.groupValues[1].toDouble()
            val lo = it.groupValues[2].toDouble()
            if (Math.abs(la) <= 90 && Math.abs(lo) <= 180) return listOf(Place("Custom location", la, lo))
        }
        val first = try { nominatim(q) } catch (e: Exception) { null }
        if (!first.isNullOrEmpty()) return first
        return openMeteo(q) // throws if this one fails too
    }

    private suspend fun nominatim(q: String): List<Place> {
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1" +
            "&countrycodes=us&limit=8&q=" + URLEncoder.encode(q, "UTF-8")
        val arr = JSONArray(Api.getText(url))
        data class Hit(val place: Place, val isPlace: Boolean)
        val hits = ArrayList<Hit>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val la = o.optString("lat").toDoubleOrNull() ?: continue
            val lo = o.optString("lon").toDoubleOrNull() ?: continue
            val a = o.optJSONObject("address")
            fun f(k: String) = a?.optString(k).orEmpty()
            val city = f("city").ifBlank { f("town") }.ifBlank { f("village") }
                .ifBlank { f("hamlet") }.ifBlank { f("municipality") }.ifBlank { f("suburb") }
            val st = abbr(f("state"))
            val zip = f("postcode")
            val head = city.ifBlank { o.optString("name") }.ifBlank { if (zip.isNotBlank()) "ZIP $zip" else "" }
            if (head.isBlank()) continue
            val name = if (st.isNotBlank()) "$head, $st" else head
            val cat = o.optString("category")
            hits.add(Hit(Place(name, la, lo), cat == "place" || cat == "boundary"))
        }
        val pool = if (hits.any { it.isPlace }) hits.filter { it.isPlace } else hits
        return pool.map { it.place }.distinctBy { it.name }.take(6)
    }

    private suspend fun openMeteo(q: String): List<Place> {
        var city = q
        var st: String? = null
        val comma = q.indexOf(',')
        if (comma > 0) {
            city = q.substring(0, comma).trim()
            st = q.substring(comma + 1).trim()
        } else {
            val m = Regex("""^(.*\S)\s+([A-Za-z]{2})$""").find(q)
            if (m != null && STATES.containsKey(m.groupValues[2].uppercase())) {
                city = m.groupValues[1]
                st = m.groupValues[2]
            }
        }
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=20&language=en&format=json&name=" +
            URLEncoder.encode(city, "UTF-8")
        val res = Api.getJson(url).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until res.length()) {
            val o = res.getJSONObject(i)
            if (o.optString("country_code") != "US") continue
            val admin = o.optString("admin1")
            val key = abbr(admin)
            if (st != null && !admin.equals(st, true) && !key.equals(st, true)) continue
            out.add(Place("${o.optString("name")}, $key", o.optDouble("latitude"), o.optDouble("longitude")))
        }
        return out.distinctBy { it.name }.take(6)
    }
}
