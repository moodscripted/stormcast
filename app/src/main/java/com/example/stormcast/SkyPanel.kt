package com.example.stormcast

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Moon, planets, ISS passes and eclipses for the selected location. */
class SkyPanel(private val ctx: Context) : Panel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, ctx.dp(4), 0, ctx.dp(16))
    }

    override val root: View = ScrollView(ctx).apply {
        isFillViewport = true
        addView(box)
    }

    private class IssResult(val passes: List<SatPass>?, val tleAgeDays: Double?, val error: String?, val stale: Boolean)

    override fun refresh(lat: Double, lon: Double) {
        job?.cancel()
        box.removeAllViews()
        box.addView(ctx.body("Working out the sky…"))
        job = scope.launch {
            try {
                val zone = withContext(Dispatchers.IO) { zoneFor(lat, lon) }
                val now = Instant.now()
                // Astronomy maths is CPU work; keep it off the main thread.
                val sky = withContext(Dispatchers.Default) {
                    Computed(
                        Sky.moon(lat, lon, now),
                        Sky.planets(lat, lon, now),
                        Sky.lunarEclipses(lat, lon, now),
                        Sky.solarEclipsesLocal(lat, lon, now),
                        Sky.solarEclipseGlobal(now)
                    )
                }
                withContext(Dispatchers.Main) { render(sky, null, zone, lat, lon, now) }
                // The ISS needs a network fetch, so it fills in after the rest is on screen.
                val iss = withContext(Dispatchers.IO) { loadIss(lat, lon, now) }
                withContext(Dispatchers.Main) { render(sky, iss, zone, lat, lon, now) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    box.removeAllViews()
                    box.addView(ctx.heading("Sky unavailable"))
                    box.addView(ctx.body(e.message ?: "Something went wrong"))
                }
            }
        }
    }

    private class Computed(
        val moon: MoonInfo,
        val planets: List<PlanetInfo>,
        val lunar: Pair<LunarEclipseEvent, LunarEclipseEvent?>,
        val solarLocal: Pair<SolarEclipseLocal, SolarEclipseLocal?>,
        val solarGlobal: SolarEclipseGlobal
    )

    /** The place's own time zone from NWS when available, else the phone's. */
    private suspend fun zoneFor(lat: Double, lon: Double): ZoneId = try {
        val id = Api.getJson("https://api.weather.gov/points/${fmt(lat)},${fmt(lon)}")
            .getJSONObject("properties").optString("timeZone")
        if (id.isNotBlank()) ZoneId.of(id) else ZoneId.systemDefault()
    } catch (e: Exception) {
        ZoneId.systemDefault()
    }

    private suspend fun loadIss(lat: Double, lon: Double, now: Instant): IssResult {
        return try {
            Api.takeStaleNote() // clear any note left by an earlier request
            val text = Api.getText("https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE")
            val stale = Api.takeStaleNote() != null
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val l1 = lines.firstOrNull { it.startsWith("1 ") } ?: return IssResult(null, null, "No ISS data received", false)
            val l2 = lines.firstOrNull { it.startsWith("2 ") } ?: return IssResult(null, null, "No ISS data received", false)
            val sat = Sgp4(l1, l2)
            val nowJd = now.toEpochMilli() / 86400000.0 + 2440587.5
            val age = nowJd - sat.epochJd
            // Predictions drift as the TLE ages (the ISS also reboosts); beyond ~2 weeks they are not worth showing.
            if (age > 14.0) return IssResult(null, age, "ISS data is ${age.roundToInt()} days old", stale)
            val passes = IssPasses.find(sat, lat, lon, now.toEpochMilli(), 10.0, 10.0)
            IssResult(passes, age, null, stale)
        } catch (e: Exception) {
            IssResult(null, null, e.message ?: "Couldn't load ISS data", false)
        }
    }

    // ------------------------------------------------------------------ UI

    private fun render(c: Computed, iss: IssResult?, zone: ZoneId, lat: Double, lon: Double, now: Instant) {
        box.removeAllViews()
        val tTime = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(zone)
        val tDay = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US).withZone(zone)
        val tFull = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US).withZone(zone)
        val tLong = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withZone(zone)
        fun time(i: Instant?) = i?.let { tTime.format(it) } ?: "—"
        fun full(i: Instant) = tFull.format(i)

        // ---- Moon
        val m = c.moon
        box.addView(ctx.heading("Moon"))
        val where = if (m.altitude > 0)
            "Up now, ${m.altitude.roundToInt()}° high in the ${Sky.compass(m.azimuth)}"
        else "Below the horizon right now"
        val moonText = buildString {
            append("${m.emoji}  ${m.phaseName}\n")
            append("${(m.illumination * 100).roundToInt()}% lit · ${"%.1f".format(Locale.US, m.ageDays)} days old\n")
            append(where).append('\n')
            append("Rise ${time(m.rise)} · Set ${time(m.set)}")
        }
        box.addView(ctx.card(moonText, 0xFF8FA3C4.toInt()))
        box.addView(ctx.card(
            m.upcoming.joinToString("\n") { "${it.emoji}  ${it.name} — ${full(it.time)}" },
            0xFF8FA3C4.toInt()
        ))

        // ---- Planets
        box.addView(ctx.heading("Planets tonight"))
        val good = c.planets.filter { it.goodTonight }
        if (good.isEmpty()) box.addView(ctx.body("No bright planets are well placed in tonight's dark hours."))
        for (p in good) box.addView(ctx.card(planetText(p, tTime, "Best"), 0xFFFFCA28.toInt()))
        val rest = c.planets.filter { !it.goodTonight }
        if (rest.isNotEmpty()) {
            box.addView(ctx.body(
                "Not well placed tonight: " + rest.joinToString(", ") {
                    if (it.naked) it.name else "${it.name} (needs a telescope)"
                }
            ))
        }

        // ---- ISS
        box.addView(ctx.heading("International Space Station"))
        when {
            iss == null -> box.addView(ctx.body("Loading ISS passes…"))
            iss.passes == null -> box.addView(ctx.body("ISS passes unavailable: ${iss.error}"))
            else -> {
                val seen = iss.passes.filter { it.visStartMs != null }
                if (seen.isEmpty()) {
                    box.addView(ctx.body("No visible passes in the next 10 days. It needs a dark sky while the station is still in sunlight."))
                }
                for (p in seen.take(6)) {
                    val vsMs = p.visStartMs ?: continue
                    val veMs = p.visEndMs ?: continue
                    val vs = Instant.ofEpochMilli(vsMs)
                    val ve = Instant.ofEpochMilli(veMs)
                    val peakVisible = p.maxMs in vsMs..veMs
                    val text = buildString {
                        append("${tDay.format(vs)}, ${tTime.format(vs)}–${tTime.format(ve)}\n")
                        append("Rises ${IssPasses.compass(p.riseAz)} · sets ${IssPasses.compass(p.setAz)}\n")
                        append("Peaks ${p.maxEl.roundToInt()}° in the ${IssPasses.compass(p.maxAz)} at ${tTime.format(Instant.ofEpochMilli(p.maxMs))}")
                        if (!peakVisible) append(" (not lit)")
                    }
                    box.addView(ctx.card(text, 0xFF60A5FA.toInt()))
                }
                if (seen.isNotEmpty()) {
                    box.addView(ctx.body("Times are when it's visible: a dark sky with the station in sunlight. It looks like a fast, steady, bright star with no blinking."))
                }
                if (iss.stale) box.addView(ctx.body("Using saved ISS data; connect to refresh."))
            }
        }

        // ---- Eclipses
        box.addView(ctx.heading("Eclipses"))
        val (lunar, lunarVis) = c.lunar
        box.addView(ctx.card(
            "Next lunar eclipse\n${lunar.kind} · ${tLong.format(lunar.peak)}, peak ${tTime.format(lunar.peak)}\n" +
                    if (lunar.visible) "The Moon is up at peak where you are."
                    else "Moon is below your horizon at peak.",
            0xFFEF5350.toInt()
        ))
        if (!lunar.visible && lunarVis != null) {
            box.addView(ctx.card(
                "Next lunar eclipse you can see\n${lunarVis.kind} · ${tLong.format(lunarVis.peak)}, peak ${tTime.format(lunarVis.peak)}",
                0xFFEF5350.toInt()
            ))
        }
        val (solar, solarVis) = c.solarLocal
        box.addView(ctx.card(solarText("Next solar eclipse from here", solar, tLong, tTime), 0xFFFFA726.toInt()))
        if (!solar.visible && solarVis != null) {
            box.addView(ctx.card(solarText("Next one with the Sun up", solarVis, tLong, tTime), 0xFFFFA726.toInt()))
        }
        val g = c.solarGlobal
        box.addView(ctx.card(
            "Next solar eclipse anywhere on Earth\n${g.kind} · ${tLong.format(g.peak)}",
            0xFFFFA726.toInt()
        ))
        box.addView(ctx.body("Eclipse times are for the peak, in the location's time zone. Never look at the Sun without proper eclipse glasses."))

        box.addView(ctx.body(
            "Comets aren't listed: there is no dependable free feed of which ones are visible to the eye. " +
                    "Sky maths by Astronomy Engine (MIT); ISS orbit data from CelesTrak."
        ))
    }

    private fun planetText(p: PlanetInfo, tTime: DateTimeFormatter, bestWord: String): String {
        val mag = String.format(Locale.US, "%.1f", p.magnitude)
        return buildString {
            append("${p.name} · magnitude $mag")
            if (!p.naked) append(" · needs a telescope")
            append('\n')
            append("$bestWord around ${p.bestTime?.let { tTime.format(it) } ?: "—"}: ")
            append("${p.bestAltitude.roundToInt()}° up in the ${Sky.compass(p.bestAzimuth)}\n")
            append("Rises ${p.rise?.let { tTime.format(it) } ?: "—"} · sets ${p.set?.let { tTime.format(it) } ?: "—"}")
        }
    }

    private fun solarText(title: String, e: SolarEclipseLocal, tLong: DateTimeFormatter, tTime: DateTimeFormatter): String {
        val pct = (e.obscuration * 100).let { if (it >= 99.95) "100" else String.format(Locale.US, "%.0f", it) }
        return buildString {
            append("$title\n")
            append("${e.kind} · ${tLong.format(e.peak)}\n")
            append("$pct% of the Sun covered at peak (${tTime.format(e.peak)})\n")
            append("${tTime.format(e.begin)} to ${tTime.format(e.end)}")
            if (!e.visible) append("\nThe Sun is below your horizon at peak.")
        }
    }
}
