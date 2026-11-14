package com.example.stormcast

import android.content.Context
import java.util.Locale
import kotlin.math.roundToInt

/** Imperial (default) or metric display, stored in the app prefs. */
object Units {
    private const val PREFS = "stormcast"

    fun metric(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("metric", false)

    fun setMetric(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("metric", v).apply()
    }

    /** Query suffix for NWS forecast endpoints. */
    fun nwsQuery(ctx: Context): String = if (metric(ctx)) "?units=si" else ""

    fun temp(ctx: Context, c: Double): String =
        "${(if (metric(ctx)) c else c * 9.0 / 5.0 + 32.0).roundToInt()}°"

    fun speedKmh(ctx: Context, kmh: Double): String =
        if (metric(ctx)) "${kmh.roundToInt()} km/h" else "${(kmh * 0.621371).roundToInt()} mph"

    fun pressurePa(ctx: Context, pa: Double): String =
        if (metric(ctx)) "${(pa / 100.0).roundToInt()} hPa"
        else String.format(Locale.US, "%.2f inHg", pa / 3386.389)

    fun visibilityM(ctx: Context, m: Double): String =
        if (metric(ctx)) String.format(Locale.US, "%.0f km", m / 1000.0)
        else String.format(Locale.US, "%.0f mi", m / 1609.344)

    fun distanceKm(ctx: Context, km: Double): String =
        if (metric(ctx)) "${km.roundToInt()} km" else "${(km * 0.621371).roundToInt()} mi"

    /** Knots with the local-unit equivalent, e.g. "65 kt (75 mph)". */
    fun knots(ctx: Context, kt: Int): String =
        if (metric(ctx)) "$kt kt (${(kt * 1.852).roundToInt()} km/h)"
        else "$kt kt (${(kt * 1.15078).roundToInt()} mph)"
}
