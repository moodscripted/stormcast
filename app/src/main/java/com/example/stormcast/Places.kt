package com.example.stormcast

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Place(val name: String, val lat: Double, val lon: Double)

/** Saved locations, stored as a JSON array in the app prefs. */
object Places {
    private const val PREFS = "stormcast"
    private const val KEY = "places"
    const val MAX = 8

    fun all(ctx: Context): List<Place> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Place(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<Place>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** Returns false when the list is full. */
    fun add(ctx: Context, p: Place): Boolean {
        val list = all(ctx).toMutableList()
        if (list.size >= MAX) return false
        list.add(p)
        save(ctx, list)
        return true
    }

    fun remove(ctx: Context, index: Int) {
        val list = all(ctx).toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            save(ctx, list)
        }
    }
}
