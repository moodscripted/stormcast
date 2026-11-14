package com.example.stormcast

import io.github.cosinekitty.astronomy.Aberration
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.Direction
import io.github.cosinekitty.astronomy.EquatorEpoch
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.equator
import io.github.cosinekitty.astronomy.horizon
import io.github.cosinekitty.astronomy.illumination
import io.github.cosinekitty.astronomy.moonPhase
import io.github.cosinekitty.astronomy.nextLocalSolarEclipse
import io.github.cosinekitty.astronomy.nextLunarEclipse
import io.github.cosinekitty.astronomy.nextMoonQuarter
import io.github.cosinekitty.astronomy.searchGlobalSolarEclipse
import io.github.cosinekitty.astronomy.searchLocalSolarEclipse
import io.github.cosinekitty.astronomy.searchLunarEclipse
import io.github.cosinekitty.astronomy.searchMoonQuarter
import io.github.cosinekitty.astronomy.searchRiseSet
import java.time.Instant

// Pure Kotlin (no Android classes) so the maths can be unit-tested on a plain JVM.

data class MoonPhaseEvent(val name: String, val emoji: String, val time: Instant)

data class MoonInfo(
    val phaseName: String,
    val emoji: String,
    val phaseDeg: Double,
    val illumination: Double,      // 0..1
    val ageDays: Double,
    val altitude: Double,
    val azimuth: Double,
    val rise: Instant?,
    val set: Instant?,
    val upcoming: List<MoonPhaseEvent>
)

data class PlanetInfo(
    val name: String,
    val magnitude: Double,
    val naked: Boolean,            // visible without optical aid
    val altitudeNow: Double,
    val azimuthNow: Double,
    val rise: Instant?,
    val set: Instant?,
    val bestAltitude: Double,      // highest altitude during tonight's dark window
    val bestAzimuth: Double,
    val bestTime: Instant?,
    val goodTonight: Boolean
)

data class LunarEclipseEvent(
    val kind: String, val peak: Instant, val moonAltitude: Double, val visible: Boolean
)

data class SolarEclipseLocal(
    val kind: String, val obscuration: Double,
    val begin: Instant, val peak: Instant, val end: Instant, val sunAltitudePeak: Double,
    val visible: Boolean
)

data class SolarEclipseGlobal(val kind: String, val peak: Instant)

object Sky {

    private fun time(i: Instant) = Time.fromMillisecondsSince1970(i.toEpochMilli())
    private fun instant(t: Time) = Instant.ofEpochMilli(t.toMillisecondsSince1970())

    fun compass(deg: Double): String {
        val d = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return d[(Math.round(((deg % 360 + 360) % 360) / 22.5).toInt()) % 16]
    }

    private data class Pos(val alt: Double, val az: Double)

    private fun position(body: Body, t: Time, obs: Observer): Pos {
        val eq = equator(body, t, obs, EquatorEpoch.OfDate, Aberration.Corrected)
        val h = horizon(t, obs, eq.ra, eq.dec, Refraction.Normal)
        return Pos(h.altitude, h.azimuth)
    }

    // ---------------------------------------------------------------- Moon

    private fun phaseLabel(deg: Double): Pair<String, String> = when {
        deg < 22.5 || deg >= 337.5 -> "New Moon" to "🌑"
        deg < 67.5 -> "Waxing Crescent" to "🌒"
        deg < 112.5 -> "First Quarter" to "🌓"
        deg < 157.5 -> "Waxing Gibbous" to "🌔"
        deg < 202.5 -> "Full Moon" to "🌕"
        deg < 247.5 -> "Waning Gibbous" to "🌖"
        deg < 292.5 -> "Last Quarter" to "🌗"
        else -> "Waning Crescent" to "🌘"
    }

    fun moon(lat: Double, lon: Double, now: Instant): MoonInfo {
        val t = time(now)
        val obs = Observer(lat, lon, 0.0)
        val deg = moonPhase(t)
        val (name, emoji) = phaseLabel(deg)
        val pos = position(Body.Moon, t, obs)

        val quarterNames = listOf(
            "New Moon" to "🌑", "First Quarter" to "🌓",
            "Full Moon" to "🌕", "Last Quarter" to "🌗"
        )
        val upcoming = ArrayList<MoonPhaseEvent>()
        var q = searchMoonQuarter(t)
        repeat(4) {
            val (n, e) = quarterNames[q.quarter]
            upcoming.add(MoonPhaseEvent(n, e, instant(q.time)))
            q = nextMoonQuarter(q)
        }

        return MoonInfo(
            phaseName = name, emoji = emoji, phaseDeg = deg,
            illumination = illumination(Body.Moon, t).phaseFraction,
            ageDays = deg / 360.0 * 29.530588861,
            altitude = pos.alt, azimuth = pos.az,
            rise = searchRiseSet(Body.Moon, obs, Direction.Rise, t, 2.0)?.let(::instant),
            set = searchRiseSet(Body.Moon, obs, Direction.Set, t, 2.0)?.let(::instant),
            upcoming = upcoming
        )
    }

    // ---------------------------------------------------------------- Planets

    private val PLANETS = listOf(
        Body.Mercury to true, Body.Venus to true, Body.Mars to true,
        Body.Jupiter to true, Body.Saturn to true,
        Body.Uranus to false, Body.Neptune to false
    )

    /** Tonight = the first stretch (within 24 h) where the Sun is more than 6 degrees below the horizon. */
    fun planets(lat: Double, lon: Double, now: Instant): List<PlanetInfo> {
        val obs = Observer(lat, lon, 0.0)
        val t0 = time(now)
        val stepDays = 15.0 / 1440.0
        val n = 96
        val times = (0..n).map { t0.addDays(it * stepDays) }
        val sunAlt = times.map { position(Body.Sun, it, obs).alt }
        val i0 = sunAlt.indexOfFirst { it < -6.0 }
        val i1 = if (i0 < 0) -1 else (i0 until sunAlt.size).firstOrNull { sunAlt[it] >= -6.0 } ?: sunAlt.size

        val out = ArrayList<PlanetInfo>()
        for ((body, naked) in PLANETS) {
            val nowPos = position(body, t0, obs)
            var bestAlt = -90.0
            var bestAz = 0.0
            var bestT: Time? = null
            if (i0 >= 0) {
                for (i in i0 until i1) {
                    val p = position(body, times[i], obs)
                    if (p.alt > bestAlt) { bestAlt = p.alt; bestAz = p.az; bestT = times[i] }
                }
            }
            out.add(
                PlanetInfo(
                    name = body.name,
                    magnitude = illumination(body, bestT ?: t0).mag,
                    naked = naked,
                    altitudeNow = nowPos.alt, azimuthNow = nowPos.az,
                    rise = searchRiseSet(body, obs, Direction.Rise, t0, 1.0)?.let(::instant),
                    set = searchRiseSet(body, obs, Direction.Set, t0, 1.0)?.let(::instant),
                    bestAltitude = bestAlt, bestAzimuth = bestAz, bestTime = bestT?.let(::instant),
                    goodTonight = bestAlt >= 10.0
                )
            )
        }
        // Visible-tonight first, then brightest first.
        return out.sortedWith(compareByDescending<PlanetInfo> { it.goodTonight }.thenBy { it.magnitude })
    }

    // ---------------------------------------------------------------- Eclipses

    /** Next lunar eclipse overall, and the next one whose peak has the Moon above your horizon. */
    fun lunarEclipses(lat: Double, lon: Double, now: Instant): Pair<LunarEclipseEvent, LunarEclipseEvent?> {
        val obs = Observer(lat, lon, 0.0)
        var e = searchLunarEclipse(time(now))
        var first: LunarEclipseEvent? = null
        var visible: LunarEclipseEvent? = null
        repeat(10) {
            val alt = position(Body.Moon, e.peak, obs).alt
            val ev = LunarEclipseEvent(e.kind.name, instant(e.peak), alt, alt > 0.0)
            if (first == null) first = ev
            if (visible == null && ev.visible) visible = ev
            if (first != null && visible != null) return first!! to visible
            e = nextLunarEclipse(e.peak)
        }
        return first!! to visible
    }

    /** Next solar eclipse seen at all from this spot (partial counts), and the next with the Sun up at peak. */
    fun solarEclipsesLocal(lat: Double, lon: Double, now: Instant): Pair<SolarEclipseLocal, SolarEclipseLocal?> {
        val obs = Observer(lat, lon, 0.0)
        var e = searchLocalSolarEclipse(time(now), obs)
        var first: SolarEclipseLocal? = null
        var visible: SolarEclipseLocal? = null
        repeat(8) {
            val vis = e.peak.altitude > 0.0
            val ev = SolarEclipseLocal(
                e.kind.name, e.obscuration,
                instant(e.partialBegin.time), instant(e.peak.time), instant(e.partialEnd.time),
                e.peak.altitude, vis
            )
            if (first == null) first = ev
            if (visible == null && vis) visible = ev
            if (first != null && visible != null) return first!! to visible
            e = nextLocalSolarEclipse(e.peak.time, obs)
        }
        return first!! to visible
    }

    fun solarEclipseGlobal(now: Instant): SolarEclipseGlobal {
        val g = searchGlobalSolarEclipse(time(now))
        return SolarEclipseGlobal(g.kind.name, instant(g.peak))
    }
}
