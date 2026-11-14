package com.example.stormcast

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One pass of a satellite over an observer. Times are Unix milliseconds (UTC). */
data class SatPass(
    val riseMs: Long, val riseAz: Double,
    val maxMs: Long, val maxEl: Double, val maxAz: Double,
    val setMs: Long, val setAz: Double,
    /** Part of the pass that can actually be seen: observer in darkness and satellite in sunlight. Null if none. */
    val visStartMs: Long?, val visEndMs: Long?
)

/** Pass prediction on top of [Sgp4]. Pure Kotlin. Accuracy is good to about a minute, which is plenty for "go outside" use. */
object IssPasses {
    private const val RAD = PI / 180.0
    private const val EARTH_R = 6378.135
    // WGS-84
    private const val A = 6378.137
    private const val F = 1.0 / 298.257223563

    fun gmst(jdUt: Double): Double {
        val t = (jdUt - 2451545.0) / 36525.0
        var s = -6.2e-6 * t * t * t + 0.093104 * t * t + (876600.0 * 3600 + 8640184.812866) * t + 67310.54841
        s = (s * RAD / 240.0) % (2 * PI)
        if (s < 0) s += 2 * PI
        return s
    }

    /** Low-precision geocentric Sun direction (unit vector, equatorial frame of date). Good to ~0.01 degree. */
    fun sunHat(jdUt: Double): DoubleArray {
        val n = jdUt - 2451545.0
        val l = (280.460 + 0.9856474 * n) * RAD
        val g = (357.528 + 0.9856003 * n) * RAD
        val lam = l + (1.915 * sin(g) + 0.020 * sin(2 * g)) * RAD
        val eps = (23.439 - 0.0000004 * n) * RAD
        return doubleArrayOf(cos(lam), cos(eps) * sin(lam), sin(eps) * sin(lam))
    }

    /** Cylindrical Earth-shadow test. */
    fun sunlit(r: DoubleArray, sun: DoubleArray): Boolean {
        val d = r[0] * sun[0] + r[1] * sun[1] + r[2] * sun[2]
        if (d >= 0) return true
        val px = r[0] - d * sun[0]
        val py = r[1] - d * sun[1]
        val pz = r[2] - d * sun[2]
        return sqrt(px * px + py * py + pz * pz) > EARTH_R
    }

    private class Obs(latDeg: Double, lonDeg: Double) {
        val lat = latDeg * RAD
        val lon = lonDeg * RAD
        val sl = sin(lat)
        val cl = cos(lat)
        val so = sin(lon)
        val co = cos(lon)
        val pos: DoubleArray

        init {
            val e2 = F * (2 - F)
            val n = A / sqrt(1 - e2 * sl * sl)
            pos = doubleArrayOf(n * cl * co, n * cl * so, n * (1 - e2) * sl)
        }

        /** Elevation and azimuth (degrees) of a TEME position given GMST. */
        fun look(r: DoubleArray, gst: Double): DoubleArray {
            val cg = cos(gst)
            val sg = sin(gst)
            // TEME -> ECEF (rotate by -gst about Z)
            val x = cg * r[0] + sg * r[1]
            val y = -sg * r[0] + cg * r[1]
            val z = r[2]
            val dx = x - pos[0]
            val dy = y - pos[1]
            val dz = z - pos[2]
            val east = -so * dx + co * dy
            val north = -sl * co * dx - sl * so * dy + cl * dz
            val up = cl * co * dx + cl * so * dy + sl * dz
            val range = sqrt(dx * dx + dy * dy + dz * dz)
            val el = asin(up / range) / RAD
            var az = atan2(east, north) / RAD
            if (az < 0) az += 360.0
            return doubleArrayOf(el, az)
        }

        /** Sun altitude (degrees) at this site. [sun] is in the same inertial frame as TEME. */
        fun sunAlt(sun: DoubleArray, gst: Double): Double {
            // local "up" in ECEF is (cl*co, cl*so, sl); rotate to inertial by +gst
            val ux = cl * co
            val uy = cl * so
            val uz = sl
            val cg = cos(gst)
            val sg = sin(gst)
            val ix = cg * ux - sg * uy
            val iy = sg * ux + cg * uy
            return asin(ix * sun[0] + iy * sun[1] + uz * sun[2]) / RAD
        }
    }

    /**
     * Passes between [fromMs] and [fromMs] + [days]. Only passes peaking at [minMaxEl] degrees or more are returned.
     * Each carries its visible window when the sky is dark (Sun below -6 degrees) and the satellite is sunlit.
     */
    fun find(
        sat: Sgp4, lat: Double, lon: Double, fromMs: Long, days: Double,
        minMaxEl: Double = 10.0, stepSec: Int = 30
    ): List<SatPass> {
        val obs = Obs(lat, lon)
        val out = ArrayList<SatPass>()
        val stepMs = stepSec * 1000L
        val endMs = fromMs + (days * 86400000.0).toLong()

        var inPass = false
        var riseMs = 0L; var riseAz = 0.0
        var maxMs = 0L; var maxEl = -90.0; var maxAz = 0.0
        var lastMs = 0L; var lastAz = 0.0
        var visStart: Long? = null; var visEnd: Long? = null

        var t = fromMs
        while (t <= endMs) {
            val jd = t / 86400000.0 + 2440587.5
            val r = sat.propagate((jd - sat.epochJd) * 1440.0)?.first
            if (r == null) { t += stepMs; continue }
            val gst = gmst(jd)
            val la = obs.look(r, gst)
            val el = la[0]
            val az = la[1]
            if (el > 0) {
                if (!inPass) {
                    inPass = true
                    riseMs = t; riseAz = az
                    maxEl = -90.0; visStart = null; visEnd = null
                }
                if (el > maxEl) { maxEl = el; maxMs = t; maxAz = az }
                val sun = sunHat(jd)
                if (obs.sunAlt(sun, gst) < -6.0 && sunlit(r, sun)) {
                    if (visStart == null) visStart = t
                    visEnd = t
                }
                lastMs = t; lastAz = az
            } else if (inPass) {
                inPass = false
                if (maxEl >= minMaxEl) {
                    out.add(SatPass(riseMs, riseAz, maxMs, maxEl, maxAz, lastMs, lastAz, visStart, visEnd))
                }
            }
            t += stepMs
        }
        // A pass still in progress at the end of the window is dropped; the window is days long, so that is fine.
        return out
    }

    fun compass(deg: Double): String {
        val names = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return names[(((deg % 360 + 360) % 360) / 22.5 + 0.5).toInt() % 16]
    }
}
