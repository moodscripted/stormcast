package com.example.stormcast

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Near-earth SGP4 (WGS-72, "improved" mode), ported from the public-domain
 * Vallado/Hoots reference implementation as used by the Python `sgp4` package.
 * Deep-space objects (period >= 225 min) are rejected; the ISS is nowhere near that.
 * Pure Kotlin, no Android imports, so it can be unit-tested on a plain JVM.
 */
class Sgp4(line1: String, line2: String) {
    // gravity constants (WGS-72)
    private val re = 6378.135
    private val mu = 398600.8
    private val xke = 60.0 / sqrt(re * re * re / mu)
    private val j2 = 0.001082616
    private val j3 = -0.00000253881
    private val j4 = -0.00000165597
    private val j3oj2 = j3 / j2

    /** Epoch as Julian date (UT). */
    val epochJd: Double

    private val bstar: Double
    private val ecco: Double
    private val argpo: Double
    private val inclo: Double
    private val mo: Double
    private val nodeo: Double

    private var noUnkozai = 0.0
    private var isimp = 0
    private var aycof = 0.0
    private var con41 = 0.0
    private var cc1 = 0.0
    private var cc4 = 0.0
    private var cc5 = 0.0
    private var d2 = 0.0
    private var d3 = 0.0
    private var d4 = 0.0
    private var delmo = 0.0
    private var eta = 0.0
    private var argpdot = 0.0
    private var omgcof = 0.0
    private var sinmao = 0.0
    private var t2cof = 0.0
    private var t3cof = 0.0
    private var t4cof = 0.0
    private var t5cof = 0.0
    private var x1mth2 = 0.0
    private var x7thm1 = 0.0
    private var mdot = 0.0
    private var nodedot = 0.0
    private var xlcof = 0.0
    private var xmcof = 0.0
    private var nodecf = 0.0

    init {
        val l1 = line1.trimEnd()
        val l2 = line2.trimEnd()
        require(l1.length >= 63 && l1.startsWith("1 ")) { "bad TLE line 1" }
        require(l2.length >= 63 && l2.startsWith("2 ")) { "bad TLE line 2" }
        val twoDigitYear = l1.substring(18, 20).trim().toInt()
        val epochDays = l1.substring(20, 32).trim().toDouble()
        val bs = l1.substring(53, 59).trim().let { s ->
            val sign = if (s.startsWith("-")) -1.0 else 1.0
            val digits = s.trimStart('-', '+', ' ')
            sign * ("0.$digits").toDouble()
        }
        val bexp = l1.substring(59, 61).trim().replace("+", "").toInt()
        bstar = bs * 10.0.pow(bexp)

        val deg2rad = PI / 180.0
        val xpdotp = 1440.0 / (2.0 * PI)
        inclo = l2.substring(8, 16).trim().toDouble() * deg2rad
        nodeo = l2.substring(17, 25).trim().toDouble() * deg2rad
        ecco = ("0." + l2.substring(26, 33).trim().replace(' ', '0')).toDouble()
        argpo = l2.substring(34, 42).trim().toDouble() * deg2rad
        mo = l2.substring(43, 51).trim().toDouble() * deg2rad
        val noKozai = l2.substring(52, 63).trim().toDouble() / xpdotp

        val year = if (twoDigitYear < 57) twoDigitYear + 2000 else twoDigitYear + 1900
        // JD of Jan 0.0 of `year` (i.e. Dec 31 00:00 of the previous year) + fractional day-of-year
        epochJd = jdOfYearStart(year) - 1.0 + epochDays

        initialize(noKozai)
    }

    private fun initialize(noKozai: Double) {
        val temp4 = 1.5e-12
        val ss = 78.0 / re + 1.0
        val qzms2ttemp = (120.0 - 78.0) / re
        val qzms2t = qzms2ttemp.pow(4)
        val x2o3 = 2.0 / 3.0

        // ---- initl ----
        val eccsq = ecco * ecco
        val omeosq = 1.0 - eccsq
        val rteosq = sqrt(omeosq)
        val cosio = cos(inclo)
        val cosio2 = cosio * cosio
        val ak = (xke / noKozai).pow(x2o3)
        val d1 = 0.75 * j2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq)
        var del = d1 / (ak * ak)
        val adel = ak * (1.0 - del * del - del * (1.0 / 3.0 + 134.0 * del * del / 81.0))
        del = d1 / (adel * adel)
        noUnkozai = noKozai / (1.0 + del)
        val ao = (xke / noUnkozai).pow(x2o3)
        val sinio = sin(inclo)
        val po = ao * omeosq
        val con42 = 1.0 - 5.0 * cosio2
        con41 = -con42 - cosio2 - cosio2
        val posq = po * po
        val rp = ao * (1.0 - ecco)

        require(2.0 * PI / noUnkozai < 225.0) { "deep-space orbit not supported" }

        isimp = 0
        if (rp < 220.0 / re + 1.0) isimp = 1
        var sfour = ss
        var qzms24 = qzms2t
        val perige = (rp - 1.0) * re
        if (perige < 156.0) {
            sfour = perige - 78.0
            if (perige < 98.0) sfour = 20.0
            val qzms24temp = (120.0 - sfour) / re
            qzms24 = qzms24temp.pow(4)
            sfour = sfour / re + 1.0
        }
        val pinvsq = 1.0 / posq
        val tsi = 1.0 / (ao - sfour)
        eta = ao * ecco * tsi
        val etasq = eta * eta
        val eeta = ecco * eta
        val psisq = abs(1.0 - etasq)
        val coef = qzms24 * tsi.pow(4)
        val coef1 = coef / psisq.pow(3.5)
        val cc2 = coef1 * noUnkozai * (ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
                0.375 * j2 * tsi / psisq * con41 * (8.0 + 3.0 * etasq * (8.0 + etasq)))
        cc1 = bstar * cc2
        var cc3 = 0.0
        if (ecco > 1.0e-4) cc3 = -2.0 * coef * tsi * j3oj2 * noUnkozai * sinio / ecco
        x1mth2 = 1.0 - cosio2
        cc4 = 2.0 * noUnkozai * coef1 * ao * omeosq *
                (eta * (2.0 + 0.5 * etasq) + ecco * (0.5 + 2.0 * etasq) -
                        j2 * tsi / (ao * psisq) *
                        (-3.0 * con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta)) +
                                0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * cos(2.0 * argpo)))
        cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)
        val cosio4 = cosio2 * cosio2
        val temp1 = 1.5 * j2 * pinvsq * noUnkozai
        val temp2 = 0.5 * temp1 * j2 * pinvsq
        val temp3 = -0.46875 * j4 * pinvsq * pinvsq * noUnkozai
        mdot = noUnkozai + 0.5 * temp1 * rteosq * con41 +
                0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4)
        argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4) +
                temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4)
        val xhdot1 = -temp1 * cosio
        nodedot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * cosio2) + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio
        omgcof = bstar * cc3 * cos(argpo)
        xmcof = 0.0
        if (ecco > 1.0e-4) xmcof = -x2o3 * coef * bstar / eeta
        nodecf = 3.5 * omeosq * xhdot1 * cc1
        t2cof = 1.5 * cc1
        xlcof = if (abs(cosio + 1.0) > 1.5e-12)
            -0.25 * j3oj2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio)
        else
            -0.25 * j3oj2 * sinio * (3.0 + 5.0 * cosio) / temp4
        aycof = -0.5 * j3oj2 * sinio
        val delmotemp = 1.0 + eta * cos(mo)
        delmo = delmotemp * delmotemp * delmotemp
        sinmao = sin(mo)
        x7thm1 = 7.0 * cosio2 - 1.0

        if (isimp != 1) {
            val cc1sq = cc1 * cc1
            d2 = 4.0 * ao * tsi * cc1sq
            val temp = d2 * tsi * cc1 / 3.0
            d3 = (17.0 * ao + sfour) * temp
            d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1
            t3cof = d2 + 2.0 * cc1sq
            t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq))
            t5cof = 0.2 * (3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2 + 15.0 * cc1sq * (2.0 * d2 + cc1sq))
        }
    }

    /** Position (km) and velocity (km/s) in TEME, [tsinceMin] minutes after epoch. Null on numerical failure. */
    fun propagate(tsinceMin: Double): Pair<DoubleArray, DoubleArray>? {
        val twopi = 2.0 * PI
        val x2o3 = 2.0 / 3.0
        val vkmpersec = re * xke / 60.0
        val t = tsinceMin

        val xmdf = mo + mdot * t
        val argpdf = argpo + argpdot * t
        val nodedf = nodeo + nodedot * t
        var argpm = argpdf
        var mm = xmdf
        val t2 = t * t
        var nodem = nodedf + nodecf * t2
        var tempa = 1.0 - cc1 * t
        var tempe = bstar * cc4 * t
        var templ = t2cof * t2
        if (isimp != 1) {
            val delomg = omgcof * t
            val delmtemp = 1.0 + eta * cos(xmdf)
            val delm = xmcof * (delmtemp * delmtemp * delmtemp - delmo)
            val temp = delomg + delm
            mm = xmdf + temp
            argpm = argpdf - temp
            val t3 = t2 * t
            val t4 = t3 * t
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4
            tempe += bstar * cc5 * (sin(mm) - sinmao)
            templ = templ + t3cof * t3 + t4 * (t4cof + t * t5cof)
        }
        var nm = noUnkozai
        var em = ecco
        val inclm = inclo
        if (nm <= 0.0) return null
        val am = (xke / nm).pow(x2o3) * tempa * tempa
        nm = xke / am.pow(1.5)
        em -= tempe
        if (em >= 1.0 || em < -0.001) return null
        if (em < 1.0e-6) em = 1.0e-6
        mm += noUnkozai * templ
        var xlm = mm + argpm + nodem
        nodem = if (nodem >= 0.0) nodem % twopi else -((-nodem) % twopi)
        argpm %= twopi
        xlm %= twopi
        mm = (xlm - argpm - nodem) % twopi
        if (mm < 0) mm += twopi

        val sinim = sin(inclm)
        val cosim = cos(inclm)
        val ep = em
        val xincp = inclm
        val argpp = argpm
        val nodep = nodem
        val mp = mm
        val sinip = sinim
        val cosip = cosim

        val axnl = ep * cos(argpp)
        var temp = 1.0 / (am * (1.0 - ep * ep))
        val aynl = ep * sin(argpp) + temp * aycof
        val xl = mp + argpp + nodep + temp * xlcof * axnl
        var u = (xl - nodep) % twopi
        if (u < 0) u += twopi
        var eo1 = u
        var tem5 = 9999.9
        var ktr = 1
        var sineo1 = 0.0
        var coseo1 = 0.0
        while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = sin(eo1)
            coseo1 = cos(eo1)
            tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
            if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0.0) 0.95 else -0.95
            eo1 += tem5
            ktr++
        }
        val ecose = axnl * coseo1 + aynl * sineo1
        val esine = axnl * sineo1 - aynl * coseo1
        val el2 = axnl * axnl + aynl * aynl
        val pl = am * (1.0 - el2)
        if (pl < 0.0) return null
        val rl = am * (1.0 - ecose)
        val rdotl = sqrt(am) * esine / rl
        val rvdotl = sqrt(pl) / rl
        val betal = sqrt(1.0 - el2)
        temp = esine / (1.0 + betal)
        val sinu = am / rl * (sineo1 - aynl - axnl * temp)
        val cosu = am / rl * (coseo1 - axnl + aynl * temp)
        var su = atan2(sinu, cosu)
        val sin2u = (cosu + cosu) * sinu
        val cos2u = 1.0 - 2.0 * sinu * sinu
        temp = 1.0 / pl
        val temp1 = 0.5 * j2 * temp
        val temp2 = temp1 * temp
        val mrt = rl * (1.0 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u
        su -= 0.25 * temp2 * x7thm1 * sin2u
        val xnode = nodep + 1.5 * temp2 * cosip * sin2u
        val xinc = xincp + 1.5 * temp2 * cosip * sinip * cos2u
        val mvt = rdotl - nm * temp1 * x1mth2 * sin2u / xke
        val rvdot = rvdotl + nm * temp1 * (x1mth2 * cos2u + 1.5 * con41) / xke
        val sinsu = sin(su)
        val cossu = cos(su)
        val snod = sin(xnode)
        val cnod = cos(xnode)
        val sini = sin(xinc)
        val cosi = cos(xinc)
        val xmx = -snod * cosi
        val xmy = cnod * cosi
        val ux = xmx * sinsu + cnod * cossu
        val uy = xmy * sinsu + snod * cossu
        val uz = sini * sinsu
        val vx = xmx * cossu - cnod * sinsu
        val vy = xmy * cossu - snod * sinsu
        val vz = sini * cossu
        if (mrt < 1.0) return null // decayed
        val mr = mrt * re
        val r = doubleArrayOf(mr * ux, mr * uy, mr * uz)
        val v = doubleArrayOf(
            (mvt * ux + rvdot * vx) * vkmpersec,
            (mvt * uy + rvdot * vy) * vkmpersec,
            (mvt * uz + rvdot * vz) * vkmpersec
        )
        return Pair(r, v)
    }

    /** Position in TEME (km) at a Unix time in milliseconds. */
    fun positionAt(unixMs: Double): DoubleArray? {
        val jd = unixMs / 86400000.0 + 2440587.5
        return propagate((jd - epochJd) * 1440.0)?.first
    }

    companion object {
        /** Julian date at 00:00 UT on Jan 1 of [year] (Gregorian). */
        fun jdOfYearStart(year: Int): Double {
            val days = 365L * (year - 1970) + Math.floorDiv(year - 1969, 4) -
                    Math.floorDiv(year - 1901, 100) + Math.floorDiv(year - 1601, 400)
            return 2440587.5 + days
        }
    }
}
