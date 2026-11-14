package com.example.stormcast

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Always-on notification whose status-bar icon is the current temperature. */
object TempNotifier {
    private const val WORK = "temp_notification"
    private const val WORK_NOW = "temp_notification_now"
    const val CHANNEL = "temperature"
    const val ID = 7001
    const val PREF = "notify_temp"

    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Turn the feature on or off. When on, it posts straight away and then refreshes about every 15 minutes. */
    fun apply(ctx: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        if (enabled) {
            val req = PeriodicWorkRequestBuilder<TempWorker>(15, TimeUnit.MINUTES)
                .setConstraints(net).build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
            refreshNow(ctx)
        } else {
            wm.cancelUniqueWork(WORK)
            wm.cancelUniqueWork(WORK_NOW)
            NotificationManagerCompat.from(ctx).cancel(ID)
        }
    }

    /** One immediate update, e.g. after the location or units change. No-op when the feature is off. */
    fun refreshNow(ctx: Context) {
        val on = ctx.getSharedPreferences("stormcast", Context.MODE_PRIVATE).getBoolean(PREF, false)
        if (!on) return
        WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(
            WORK_NOW, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TempWorker>().setConstraints(net).build()
        )
    }

    /** White-on-transparent bitmap of the number, which Android tints for the status bar. */
    fun iconFor(text: String): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        // Largest size at which the text still fits the square.
        p.textSize = size * 0.9f
        val w = p.measureText(text)
        if (w > size) p.textSize = p.textSize * size / w
        val y = size / 2f - (p.descent() + p.ascent()) / 2f
        c.drawText(text, size / 2f, y, p)
        return bmp
    }

    fun post(ctx: Context, tempC: Double, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Current temperature", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
        val degrees = if (Units.metric(ctx)) tempC else tempC * 9.0 / 5.0 + 32.0
        val open = PendingIntent.getActivity(
            ctx, ID,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(IconCompat.createWithBitmap(iconFor(degrees.roundToInt().toString())))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(ID, n)
        } catch (_: SecurityException) {
        }
    }
}

class TempWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Api.init(ctx)
        val prefs = ctx.getSharedPreferences("stormcast", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(TempNotifier.PREF, false)) return Result.success()
        val lat = prefs.getFloat("lat", 25.7617f).toDouble()
        val lon = prefs.getFloat("lon", -80.1918f).toDouble()
        return try {
            val props = Api.getJson(
                "https://api.weather.gov/points/${fmt(lat)},${fmt(lon)}", allowStale = false
            ).getJSONObject("properties")
            val obs = Nws.observation(props.optString("observationStations"))?.first
                ?: return Result.success() // keep the previous notification
            val tempC = Nws.num(obs, "temperature") ?: return Result.success()
            val rel = props.optJSONObject("relativeLocation")?.optJSONObject("properties")
            val place = if (rel != null) "${rel.optString("city")}, ${rel.optString("state")}" else ""
            val desc = obs.optString("textDescription")
            val feelsC = Nws.num(obs, "heatIndex") ?: Nws.num(obs, "windChill")
            val title = listOf(Units.temp(ctx, tempC), desc).filter { it.isNotBlank() }.joinToString(" · ")
            val wind = Nws.num(obs, "windSpeed")
            val hum = Nws.num(obs, "relativeHumidity")
            val text = listOfNotNull(
                feelsC?.let { "Feels ${Units.temp(ctx, it)}" },
                wind?.let { if (it < 1.5) "Calm" else Units.speedKmh(ctx, it) },
                hum?.let { "${it.roundToInt()}% humidity" },
                place.ifBlank { null }
            ).joinToString(" · ").ifBlank { place }
            TempNotifier.post(ctx, tempC, title, text)
            Result.success()
        } catch (e: Exception) {
            Result.success() // try again next cycle; the old notification stays up
        }
    }
}
