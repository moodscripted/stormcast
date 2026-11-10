package com.example.stormcast

import android.content.Context
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

fun fmt(d: Double): String = String.format(Locale.US, "%.4f", d)

fun Context.heading(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 18f
    setTypeface(typeface, Typeface.BOLD)
    setPadding(dp(16), dp(14), dp(16), dp(4))
}

fun Context.body(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 14f
    setPadding(dp(16), dp(6), dp(16), dp(6))
}

fun Context.card(text: String, accent: Int = 0xFF1976D2.toInt()): TextView = TextView(this).apply {
    this.text = text
    textSize = 14f
    setPadding(dp(16), dp(12), dp(16), dp(12))
    background = GradientDrawable().apply {
        setColor(0xFFF4F6FA.toInt())
        cornerRadius = dp(10).toFloat()
        setStroke(dp(3), accent)
    }
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(dp(12), dp(8), dp(12), 0) }
}
