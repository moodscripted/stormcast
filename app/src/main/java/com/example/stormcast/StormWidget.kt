package com.example.stormcast

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.runBlocking

/**
 * Home-screen widget. Small size: temperature, conditions, feels-like and the worst alert.
 * Large size (resize taller/wider): adds high/low, wind, humidity, next rain, a 6-hour strip,
 * nearest storm, and up to three alerts.
 */
class StormWidget : AppWidgetProvider() {

    companion object {
        /** Ask all placed widgets to refresh (e.g. after the location changes). */
        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, StormWidget::class.java))
            if (ids.isEmpty()) return
            ctx.sendBroadcast(
                Intent(ctx, StormWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }

        private val HOUR_IDS = listOf(
            Triple(R.id.h1_t, R.id.h1_i, R.id.h1_v),
            Triple(R.id.h2_t, R.id.h2_i, R.id.h2_v),
            Triple(R.id.h3_t, R.id.h3_i, R.id.h3_v),
            Triple(R.id.h4_t, R.id.h4_i, R.id.h4_v),
            Triple(R.id.h5_t, R.id.h5_i, R.id.h5_v),
            Triple(R.id.h6_t, R.id.h6_i, R.id.h6_v)
        )
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        update(context, mgr, ids)
    }

    /** Fires when the user resizes the widget, so it can switch between small and large. */
    override fun onAppWidgetOptionsChanged(
        context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle
    ) {
        update(context, mgr, intArrayOf(id))
    }

    private fun isLarge(mgr: AppWidgetManager, id: Int): Boolean {
        val o = mgr.getAppWidgetOptions(id)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        return w >= 240 && h >= 170
    }

    private fun update(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread {
            try {
                Api.init(context)
                val prefs = context.getSharedPreferences("stormcast", Context.MODE_PRIVATE)
                val lat = prefs.getFloat("lat", 25.7617f).toDouble()
                val lon = prefs.getFloat("lon", -80.1918f).toDouble()
                val large = ids.associateWith { isLarge(mgr, it) }
                val data = try {
                    runBlocking { WidgetFeed.load(context, lat, lon, large.values.any { it }) }
                } catch (_: Exception) { null }
                for (id in ids) {
                    val v = if (large[id] == true) largeViews(context, data, lat, lon)
                    else smallViews(context, data, lat, lon)
                    mgr.updateAppWidget(id, v)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun openApp(ctx: Context, hasAlert: Boolean): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).putExtra("tab", if (hasAlert) 1 else 0),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun severityColor(sev: String): Int = when (sev) {
        "Extreme" -> 0xFFFF6B6B.toInt()
        "Severe" -> 0xFFFFA94D.toInt()
        else -> 0xFFFFE066.toInt()
    }

    private fun smallViews(ctx: Context, d: WidgetData?, lat: Double, lon: Double): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_storm)
        v.setTextViewText(R.id.widget_place, d?.place ?: "${fmt(lat)}, ${fmt(lon)}")
        v.setTextViewText(R.id.widget_temp, d?.temp ?: "--")
        val desc = d?.description?.ifBlank { null } ?: "No data"
        v.setTextViewText(
            R.id.widget_desc,
            if (d?.feelsLike != null) "$desc\nFeels like ${d.feelsLike}" else desc
        )
        val worst = d?.alerts?.firstOrNull()
        if (worst != null) {
            v.setTextViewText(R.id.widget_alert, "⚠ ${worst.event}")
            v.setTextColor(R.id.widget_alert, severityColor(worst.severity))
        } else {
            v.setTextViewText(R.id.widget_alert, "✓ No active alerts")
            v.setTextColor(R.id.widget_alert, 0xFF8FA3C4.toInt())
        }
        v.setOnClickPendingIntent(R.id.widget_root, openApp(ctx, worst != null))
        return v
    }

    private fun largeViews(ctx: Context, d: WidgetData?, lat: Double, lon: Double): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_storm_large)
        v.setTextViewText(R.id.widget_place, d?.place ?: "${fmt(lat)}, ${fmt(lon)}")
        v.setTextViewText(R.id.widget_temp, d?.temp ?: "--")
        v.setTextViewText(R.id.widget_desc, d?.description?.ifBlank { null } ?: "No data")

        val sub = listOfNotNull(
            d?.feelsLike?.let { "Feels like $it" },
            d?.high?.let { "H $it" },
            d?.low?.let { "L $it" }
        ).joinToString("  ·  ")
        v.setTextViewText(R.id.widget_sub, sub)

        setOrHide(v, R.id.widget_stats, d?.stats?.let { "💨 $it" })
        setOrHide(v, R.id.widget_rain, d?.nextRain?.let { "💧 $it" })
        setOrHide(v, R.id.widget_storm, d?.storm)

        // 6-hour strip
        val hours = d?.hours.orEmpty()
        v.setViewVisibility(R.id.widget_hours, if (hours.isEmpty()) View.GONE else View.VISIBLE)
        HOUR_IDS.forEachIndexed { i, (t, e, temp) ->
            val h = hours.getOrNull(i)
            v.setTextViewText(t, h?.label ?: "")
            v.setTextViewText(e, h?.emoji ?: "")
            v.setTextViewText(temp, h?.temp ?: "")
        }

        // Up to three alerts
        val alerts = d?.alerts.orEmpty().take(3)
        val alertIds = listOf(R.id.widget_alert, R.id.widget_alert2, R.id.widget_alert3)
        alertIds.forEachIndexed { i, id ->
            val a = alerts.getOrNull(i)
            if (a != null) {
                v.setViewVisibility(id, View.VISIBLE)
                v.setTextViewText(id, "⚠ ${a.event}")
                v.setTextColor(id, severityColor(a.severity))
            } else if (i == 0) {
                v.setViewVisibility(id, View.VISIBLE)
                v.setTextViewText(id, "✓ No active alerts")
                v.setTextColor(id, 0xFF8FA3C4.toInt())
            } else {
                v.setViewVisibility(id, View.GONE)
            }
        }
        v.setOnClickPendingIntent(R.id.widget_root, openApp(ctx, alerts.isNotEmpty()))
        return v
    }

    private fun setOrHide(v: RemoteViews, id: Int, text: String?) {
        if (text.isNullOrBlank()) {
            v.setViewVisibility(id, View.GONE)
        } else {
            v.setViewVisibility(id, View.VISIBLE)
            v.setTextViewText(id, text)
        }
    }
}
