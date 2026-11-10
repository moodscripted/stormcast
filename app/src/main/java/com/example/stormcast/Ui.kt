package com.example.stormcast

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

interface Panel {
    val root: android.view.View
    fun refresh(lat: Double, lon: Double)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.isNight(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

fun fmt(d: Double): String = String.format(Locale.US, "%.4f", d)

fun Context.heading(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 20f
    setTypeface(typeface, Typeface.BOLD)
    setPadding(dp(20), dp(18), dp(20), dp(8))
}

fun Context.body(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 15f
    setLineSpacing(dp(2), 1f)
    setPadding(dp(20), dp(8), dp(20), dp(8))
}

fun Context.card(text: String, accent: Int = 0xFF1976D2.toInt()): TextView = TextView(this).apply {
    this.text = text
    textSize = 15f
    setLineSpacing(dp(2), 1f)
    setPadding(dp(20), dp(16), dp(20), dp(16))
    background = GradientDrawable().apply {
        setColor(if (isNight()) 0xFF1C2940.toInt() else 0xFFF7F9FC.toInt())
        cornerRadius = dp(16).toFloat()
        setStroke(dp(2), accent)
    }
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(dp(16), dp(12), dp(16), 0) }
}
