package com.example.stormcast

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
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

// ---- design tokens ----

fun Context.cBg(): Int = if (isNight()) 0xFF0B1220.toInt() else 0xFFF1F4F9.toInt()
fun Context.cSurface(): Int = if (isNight()) 0xFF17233E.toInt() else 0xFFFFFFFF.toInt()
fun Context.cText(): Int = if (isNight()) 0xFFE7EEF9.toInt() else 0xFF182238.toInt()
fun Context.cDim(): Int = if (isNight()) 0xFF8FA3C4.toInt() else 0xFF5C6C8C.toInt()
fun Context.cPrimary(): Int = if (isNight()) 0xFF60A5FA.toInt() else 0xFF2563EB.toInt()
fun Context.cHeader(): Int = if (isNight()) 0xFF0D1930.toInt() else 0xFF101E42.toInt()

// ---- text ----

fun Context.heading(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 20f
    setTextColor(cText())
    setTypeface(typeface, Typeface.BOLD)
    setPadding(dp(20), dp(20), dp(20), dp(10))
}

fun Context.body(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 15f
    setTextColor(cDim())
    setLineSpacing(dp(2).toFloat(), 1f)
    setPadding(dp(20), dp(6), dp(20), dp(6))
}

// ---- cards ----

fun Context.card(text: String, accent: Int = cPrimary()): View {
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply {
            setColor(cSurface())
            cornerRadius = dp(16).toFloat()
        }
        elevation = if (isNight()) 0f else dp(1).toFloat()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(16), dp(10), dp(16), 0) }
    }
    val strip = View(this).apply {
        background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(
                dp(16).toFloat(), dp(16).toFloat(),
                0f, 0f,
                0f, 0f,
                dp(16).toFloat(), dp(16).toFloat()
            )
            setColor(accent)
        }
    }
    val tv = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(cText())
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(14), dp(14), dp(16), dp(14))
    }
    container.addView(strip, LinearLayout.LayoutParams(dp(5), LinearLayout.LayoutParams.MATCH_PARENT))
    container.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    return container
}

fun Context.forecastCard(
    name: String,
    temp: String,
    unit: String,
    short: String,
    wind: String,
    detail: String
): View {
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(cSurface())
            cornerRadius = dp(16).toFloat()
        }
        elevation = if (isNight()) 0f else dp(1).toFloat()
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(16), dp(10), dp(16), 0) }
    }
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    top.addView(TextView(this).apply {
        text = name
        textSize = 16f
        setTextColor(cPrimary())
        setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    })
    top.addView(TextView(this).apply {
        text = "$temp°$unit"
        textSize = 26f
        setTextColor(cText())
        setTypeface(typeface, Typeface.BOLD)
    })
    container.addView(top)
    container.addView(TextView(this).apply {
        text = short
        textSize = 15f
        setTextColor(cText())
        setPadding(0, dp(4), 0, 0)
    })
    container.addView(TextView(this).apply {
        text = wind
        textSize = 13f
        setTextColor(cDim())
        setPadding(0, dp(2), 0, 0)
    })
    if (detail.isNotBlank()) {
        container.addView(TextView(this).apply {
            text = detail
            textSize = 14f
            setTextColor(cDim())
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(8), 0, 0)
        })
    }
    return container
}

// ---- tabs ----

fun Context.tabRow(names: List<String>): Pair<LinearLayout, List<TextView>> {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply {
            setColor(cSurface())
            cornerRadius = dp(26).toFloat()
        }
        setPadding(dp(4), dp(4), dp(4), dp(4))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(12), dp(12), dp(12), dp(2)) }
    }
    val tabs = names.map { n ->
        TextView(this).apply {
            text = n
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(0, dp(11), 0, dp(11))
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { setMargins(dp(2), 0, dp(2), 0) }
        }
    }
    tabs.forEach { row.addView(it) }
    return row to tabs
}

fun Context.styleTab(tv: TextView, selected: Boolean) {
    tv.background = GradientDrawable().apply {
        cornerRadius = dp(22).toFloat()
        setColor(if (selected) cPrimary() else Color.TRANSPARENT)
    }
    tv.setTextColor(if (selected) 0xFFFFFFFF.toInt() else cDim())
    tv.setTypeface(tv.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
}

// ---- buttons ----

fun Context.headerButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label
    textSize = 14f
    setTextColor(0xFFFFFFFF.toInt())
    setPadding(dp(14), dp(8), dp(14), dp(8))
    background = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(0x2EFFFFFF)
        setStroke(dp(1), 0x40FFFFFF)
    }
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginStart = dp(8) }
}

fun Context.actionButton(label: String, primary: Boolean = false, onClick: () -> Unit): View =
    TextView(this).apply {
        text = label
        textSize = 14.5f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            if (primary) {
                setColor(cPrimary())
                setTextColor(0xFFFFFFFF.toInt())
            } else {
                setColor(if (isNight()) 0x1A5B9BFF.toInt() else 0x142563EB.toInt())
                setStroke(dp(1), if (isNight()) 0x3D5B9BFF.toInt() else 0x332563EB.toInt())
                setTextColor(cPrimary())
            }
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(16), dp(8), dp(16), 0) }
    }
