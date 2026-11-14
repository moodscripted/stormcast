package com.example.stormcast

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import java.util.Calendar
import java.util.concurrent.TimeUnit

object AlertScheduler {
    private const val WORK = "storm_alerts"

    fun apply(ctx: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        if (enabled) {
            val req = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        } else {
            wm.cancelUniqueWork(WORK)
        }
    }
}

/**
 * Every ~15 min: check NWS alerts and NHC storms for the current location and saved places,
 * and post notifications according to the user's settings (severity floor, watches/advisories,
 * quiet hours, storm radius).
 */
class AlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val CHANNEL = "severe_alerts"
        const val CHANNEL_STORM = "tropical_storms"
        private const val PREFS = "stormcast"
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Api.init(ctx)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wantAlerts = prefs.getBoolean("notify", false)
        val wantStorms = prefs.getBoolean("notify_storms", false)
        if (!wantAlerts && !wantStorms) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        val here = Place(
            "Current location",
            prefs.getFloat("lat", 25.7617f).toDouble(),
            prefs.getFloat("lon", -80.1918f).toDouble()
        )
        val locations = (listOf(here) + Places.all(ctx))
            .distinctBy { "%.2f,%.2f".format(it.lat, it.lon) }
            .take(6)

        if (wantAlerts) checkAlerts(ctx, prefs, locations)
        if (wantStorms) checkStorms(ctx, prefs, locations)
        return Result.success()
    }

    /** Quiet hours are 10 PM - 7 AM local time. */
    private fun inQuietHours(prefs: android.content.SharedPreferences): Boolean {
        if (!prefs.getBoolean("quiet", false)) return false
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 22 || h < 7
    }

    private fun passesFilter(prefs: android.content.SharedPreferences, a: AlertInfo): Boolean {
        val rank = Nws.severityRank(a.severity)
        if (rank >= 4) return true // Extreme always gets through
        if (rank < prefs.getInt("min_sev", 3)) return false
        val e = a.event
        return when {
            e.endsWith("Warning") -> true
            e.endsWith("Watch") -> prefs.getBoolean("incl_watch", true)
            else -> prefs.getBoolean("incl_advisory", false)
        }
    }

    private suspend fun checkAlerts(
        ctx: Context, prefs: android.content.SharedPreferences, locations: List<Place>
    ) {
        val seen = prefs.getStringSet("seen_alerts", emptySet()) ?: emptySet()
        val nowActive = HashSet<String>()
        val deferred = HashSet<String>()
        val quiet = inQuietHours(prefs)
        var failures = 0
        for (loc in locations) {
            val alerts = try {
                Nws.alerts(loc.lat, loc.lon, allowStale = false)
            } catch (e: Exception) {
                failures++
                continue
            }
            for (a in alerts) {
                if (a.id.isBlank()) continue
                nowActive.add(a.id)
                if (a.id in seen || !passesFilter(prefs, a)) continue
                if (quiet && Nws.severityRank(a.severity) < 4) {
                    deferred.add(a.id) // not recorded as seen, so it fires after quiet hours
                    continue
                }
                notify(
                    ctx, CHANNEL, "Severe weather alerts", a.id,
                    "${a.event} \u2014 ${loc.name}", a.headline.ifBlank { a.area }, 1
                )
            }
        }
        // If a lookup failed, keep old ids so we don't re-notify when it recovers.
        val keep = (if (failures == 0) nowActive else nowActive + seen) - deferred
        prefs.edit().putStringSet("seen_alerts", HashSet(keep)).apply()
    }

    private fun stageRank(cls: String, wind: Int): Int = when (cls) {
        "TD", "STD" -> 1
        "TS", "STS", "PTC" -> 2
        "HU" -> if (wind >= 96) 4 else 3
        else -> 0
    }

    private fun stageName(cls: String, wind: Int): String = when (cls) {
        "TD" -> "Tropical Depression"
        "STD" -> "Subtropical Depression"
        "TS" -> "Tropical Storm"
        "STS" -> "Subtropical Storm"
        "PTC" -> "Potential Tropical Cyclone"
        "HU" -> when {
            wind >= 137 -> "Category 5 Hurricane"
            wind >= 113 -> "Category 4 Hurricane"
            wind >= 96 -> "Category 3 Hurricane"
            wind >= 83 -> "Category 2 Hurricane"
            else -> "Category 1 Hurricane"
        }
        else -> "Storm"
    }

    private suspend fun checkStorms(
        ctx: Context, prefs: android.content.SharedPreferences, locations: List<Place>
    ) {
        val storms: JSONArray = try {
            Api.getJson("https://www.nhc.noaa.gov/CurrentStorms.json", allowStale = false)
                .optJSONArray("activeStorms") ?: JSONArray()
        } catch (e: Exception) {
            return
        }
        val old = prefs.getStringSet("storm_state", emptySet()) ?: emptySet()
        val state = HashSet<String>()
        val radiusKm = prefs.getFloat("storm_radius_km", 800f).toDouble()
        val quiet = inQuietHours(prefs)

        for (i in 0 until storms.length()) {
            val s = storms.getJSONObject(i)
            val id = s.optString("id")
            if (id.isBlank()) continue
            val name = s.optString("name")
            val cls = s.optString("classification")
            val wind = s.optString("intensity").toIntOrNull() ?: 0
            val rank = stageRank(cls, wind)
            val label = "${stageName(cls, wind)} $name"
            val suppressed = quiet && rank < 4

            // New storm / strengthened
            val oldRank = old.firstOrNull { it.startsWith("st:$id|") }?.substringAfter('|')?.toIntOrNull()
            if (suppressed) {
                // Keep the previous state so the change is announced after quiet hours.
                if (oldRank != null) state.add("st:$id|$oldRank")
            } else {
                state.add("st:$id|$rank")
                when {
                    oldRank == null -> notify(
                        ctx, CHANNEL_STORM, "Tropical storms", "st:$id",
                        "New tropical cyclone: $label",
                        "${Units.knots(ctx, wind)} sustained winds. Tap for the cone and advisories.", 3
                    )
                    rank > oldRank -> notify(
                        ctx, CHANNEL_STORM, "Tropical storms", "up:$id:$rank",
                        "$name strengthened to ${stageName(cls, wind)}",
                        "${Units.knots(ctx, wind)} sustained winds.", 3
                    )
                }
            }

            // Within the chosen distance of the current location or a saved place
            val la = s.optDouble("latitudeNumeric", Double.NaN)
            val lo = s.optDouble("longitudeNumeric", Double.NaN)
            if (la.isNaN() || lo.isNaN()) continue
            for (loc in locations) {
                val key = "near:$id:${"%.2f,%.2f".format(loc.lat, loc.lon)}"
                val km = Geo.haversineKm(loc.lat, loc.lon, la, lo)
                val wasNear = key in old
                // 15% hysteresis so a storm hovering at the edge doesn't re-notify.
                if (km <= radiusKm || (wasNear && km <= radiusKm * 1.15)) {
                    if (suppressed && !wasNear) continue
                    state.add(key)
                    if (!wasNear) {
                        notify(
                            ctx, CHANNEL_STORM, "Tropical storms", key,
                            "$name is ${Units.distanceKm(ctx, km)} from ${loc.name}",
                            "$label \u00B7 ${Units.knots(ctx, wind)} winds.", 3
                        )
                    }
                }
            }
        }
        prefs.edit().putStringSet("storm_state", state).apply()
    }

    private fun notify(
        ctx: Context, channel: String, channelName: String, key: String,
        title: String, text: String, tab: Int
    ) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(channel) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = PendingIntent.getActivity(
            ctx, key.hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra("tab", tab)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(key.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }
}
