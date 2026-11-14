package com.example.stormcast

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Row of chips under the header: one per saved place (with its current temperature),
 * the selected one highlighted, plus a "+" chip to save or add a place.
 * Tap = switch to it. Long-press = manage saved places.
 */
class PlaceBar(
    private val act: Activity,
    private val onPick: (Place) -> Unit,
    private val onAdd: (currentIsSaved: Boolean) -> Unit,
    private val onManage: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val row = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(act.dp(12), 0, act.dp(12), act.dp(10))
    }
    val view = HorizontalScrollView(act).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(act.cHeader())
        addView(row)
    }

    private val tempsC = HashMap<String, Double>()
    private val fetchedAt = HashMap<String, Long>()
    private var lastLat = 0.0
    private var lastLon = 0.0

    private fun key(p: Place) = "%.3f,%.3f".format(p.lat, p.lon)
    private fun same(p: Place, lat: Double, lon: Double) =
        Math.abs(p.lat - lat) < 0.001 && Math.abs(p.lon - lon) < 0.001

    /** Rebuild the chips for the current location and fetch any temperatures that are missing or old. */
    fun update(lat: Double, lon: Double) {
        lastLat = lat
        lastLon = lon
        build()
        val now = System.currentTimeMillis()
        for (p in Places.all(act)) {
            val k = key(p)
            if (now - (fetchedAt[k] ?: 0L) < 10 * 60 * 1000L) continue
            fetchedAt[k] = now
            scope.launch {
                val c = runCatching { Nws.conditions(p.lat, p.lon) }.getOrNull()
                val t = c?.tempC
                if (t != null) {
                    tempsC[k] = t
                    build()
                }
            }
        }
    }

    /** Forget cached temperatures so the next update fetches fresh ones (pull-to-refresh). */
    fun invalidate() { fetchedAt.clear() }

    fun destroy() { scope.cancel() }

    private fun build() {
        row.removeAllViews()
        val saved = Places.all(act)
        var currentSaved = false
        for (p in saved) {
            val sel = same(p, lastLat, lastLon)
            if (sel) currentSaved = true
            val t = tempsC[key(p)]?.let { "  " + Units.temp(act, it) } ?: ""
            row.addView(chip(p.name + t, sel) { onPick(p) })
        }
        row.addView(chip(if (currentSaved) "＋ Add place" else "＋ Save this place", false) { onAdd(currentSaved) })
    }

    private fun chip(text: String, selected: Boolean, click: () -> Unit): View = TextView(act).apply {
        this.text = text
        textSize = 13f
        maxLines = 1
        setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFCFDCF2.toInt())
        setPadding(act.dp(14), act.dp(7), act.dp(14), act.dp(7))
        background = GradientDrawable().apply {
            cornerRadius = act.dp(18).toFloat()
            setColor(if (selected) act.cPrimary() else 0x26FFFFFF)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(act.dp(4), 0, act.dp(4), 0) }
        setOnClickListener { click() }
        setOnLongClickListener { onManage(); true }
    }
}
