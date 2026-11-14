package com.example.stormcast

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "stormcast"
        private const val REQ_PERMS = 101
        private const val REQ_NOTIF = 102
    }

    private lateinit var coordsView: TextView
    private lateinit var placeBar: PlaceBar
    private lateinit var content: FrameLayout
    private lateinit var swipe: SwipeRefreshLayout
    private var placeName = "Current location"
    private lateinit var tabs: List<TextView>
    private val panels = ArrayList<Panel>()
    private var currentTab = -1
    private var lat = 25.7617
    private var lon = -80.1918

    private val tabNames = arrayOf("Forecast", "Alerts", "Radar", "Cyclones", "Sky")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(applicationContext)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        lat = prefs.getFloat("lat", lat.toFloat()).toDouble()
        lon = prefs.getFloat("lon", lon.toFloat()).toDouble()
        placeName = prefs.getString("place_name", placeName) ?: placeName

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg())
        }

        // ---- Header ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cHeader())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            elevation = dp(3).toFloat()
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val gpsBtn = headerButton("\uD83D\uDCCD GPS") { useGps() }
        val setBtn = headerButton("Set\u2026") { showCoordDialog() }
        header.addView(title)
        header.addView(headerButton("\u2699") { showSettings() })
        header.addView(gpsBtn)
        header.addView(setBtn)

        coordsView = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF9FB4D9.toInt())
            setBackgroundColor(cHeader())
            setPadding(dp(16), dp(4), dp(16), dp(12))
            setOnClickListener { showPlaces() }
        }

        placeBar = PlaceBar(
            this,
            onPick = { p -> setLocation(p.lat, p.lon, p.name) },
            onAdd = { saved -> if (saved) showCoordDialog() else promptSavePlace() },
            onManage = { showPlaces() }
        )

        // ---- Tabs ----
        val (tabRow, tabViews) = tabRow(tabNames.toList())
        tabs = tabViews
        tabViews.forEachIndexed { i, tv -> tv.setOnClickListener { selectTab(i) } }

        // ---- Content ----
        content = FrameLayout(this)
        swipe = SwipeRefreshLayout(this).apply {
            addView(content)
            setProgressBackgroundColorSchemeColor(cSurface())
            setColorSchemeColors(cPrimary())
            // Only start a refresh when the visible panel is scrolled to the top.
            setOnChildScrollUpCallback { _, _ -> panels.getOrNull(currentTab)?.root?.canScrollVertically(-1) ?: false }
            setOnRefreshListener {
                placeBar.invalidate()
                placeBar.update(lat, lon)
                panels[currentTab].refresh(lat, lon)
                postDelayed({ isRefreshing = false }, 900)
            }
        }

        root.addView(header)
        root.addView(coordsView)
        root.addView(placeBar.view)
        root.addView(tabRow)
        root.addView(
            swipe,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)

        panels.add(ForecastPanel(this))
        panels.add(AlertsPanel(this))
        panels.add(RadarPanel(this))
        panels.add(HurricanePanel(this))
        panels.add(SkyPanel(this))

        updateCoordsView()
        selectTab(intent.getIntExtra("tab", 0).coerceIn(0, panels.size - 1))
        AlertScheduler.apply(this, prefs.getBoolean("notify", false) || prefs.getBoolean("notify_storms", false))
        if (prefs.getBoolean(TempNotifier.PREF, false)) TempNotifier.apply(this, true)
    }

    private fun selectTab(i: Int) {
        currentTab = i
        if (::swipe.isInitialized) swipe.isEnabled = i != 2 // radar map uses vertical drags
        for (j in tabs.indices) styleTab(tabs[j], j == i)
        content.removeAllViews()
        content.addView(
            panels[i].root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        panels[i].refresh(lat, lon)
    }

    private fun updateCoordsView() {
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        placeBar.update(lat, lon)
        coordsView.text = "\uD83D\uDCCD $placeName \u25BE   \u00B7   ${fmt(lat)}, ${fmt(lon)}   \u00B7   v$ver"
    }

    private fun setLocation(la: Double, lo: Double, name: String = "Custom location") {
        lat = la
        lon = lo
        placeName = name
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("lat", la.toFloat())
            .putFloat("lon", lo.toFloat())
            .putString("place_name", name)
            .apply()
        updateCoordsView()
        panels[currentTab].refresh(lat, lon)
        StormWidget.refreshAll(this)
        TempNotifier.refreshNow(this)
    }

    private fun useGps() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = try {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (e: SecurityException) {
                null
            }
            if (loc != null) {
                setLocation(loc.latitude, loc.longitude, "GPS location")
            } else {
                Toast.makeText(
                    this,
                    "No last known location. Enable location services or use Set\u2026",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQ_PERMS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notifications are blocked \u2014 enable them in system settings to get alerts", Toast.LENGTH_LONG).show()
            } else {
                TempNotifier.refreshNow(this) // permission just granted: post the temperature now
            }
            return
        }
        if (requestCode == REQ_PERMS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                useGps()
            } else {
                Toast.makeText(this, "Location denied \u2014 use Set\u2026 to enter a city or ZIP", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------ saved places

    // ------------------------------------------------------------ settings

    // ------------------------------------------------------------ dialogs

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onDestroy() {
        placeBar.destroy()
        uiScope.cancel()
        super.onDestroy()
    }

    /** "Set\u2026": find a place by city and state, or ZIP code. */
    private fun showCoordDialog() {
        val d = SheetDialog(this).title("Set location")
        d.message("Enter a city and state, or a ZIP code.")
        val input = inputField("e.g. Orlando, FL  or  32801")
        val status = TextView(this).apply {
            textSize = 13.5f
            setTextColor(cDim())
            setPadding(dp(2), dp(10), 0, 0)
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        d.body.addView(input)
        d.body.addView(status)
        d.body.addView(results)

        fun choose(p: Place) {
            d.dismiss()
            setLocation(p.lat, p.lon, p.name)
        }
        fun search() {
            val q = input.text.toString().trim()
            results.removeAllViews()
            if (q.isBlank()) {
                status.text = "Type a city and state, or a ZIP code."
                return
            }
            status.setTextColor(cDim())
            status.text = "Searching\u2026"
            uiScope.launch {
                val list = try {
                    Geocode.search(q)
                } catch (e: Exception) {
                    status.setTextColor(0xFFE57373.toInt())
                    status.text = "Couldn't search \u2014 check your connection and try again."
                    return@launch
                }
                when {
                    list.isEmpty() -> {
                        status.setTextColor(0xFFE57373.toInt())
                        status.text = "No US match for \u201C$q\u201D. Try \u201CCity, ST\u201D or a 5-digit ZIP."
                    }
                    list.size == 1 -> choose(list[0])
                    else -> {
                        status.text = "Choose a place:"
                        list.forEach { p ->
                            results.addView(sheetRow("\uD83D\uDCCD", p.name, "${fmt(p.lat)}, ${fmt(p.lon)}") { choose(p) })
                        }
                    }
                }
            }
        }
        input.setOnEditorActionListener { _, _, _ -> search(); true }
        d.button("Cancel")
        d.button("Search", primary = true, dismiss = false) { search() }
        d.show()
        input.requestFocus()
    }

    // ------------------------------------------------------------ saved places

    private fun showPlaces() {
        val saved = Places.all(this)
        val d = SheetDialog(this).title("Locations")
        d.setOnDismissListener { updateCoordsView() }
        if (saved.isEmpty()) d.message("No saved places yet. Save the current location to switch between places quickly.")
        saved.forEachIndexed { i, p ->
            val del = TextView(this).apply {
                text = "\u2715"
                textSize = 16f
                setTextColor(cDim())
                setPadding(dp(14), dp(8), dp(10), dp(8))
                setOnClickListener {
                    Places.remove(this@MainActivity, i)
                    d.dismiss()
                    showPlaces()
                }
            }
            d.body.addView(sheetRow("\uD83D\uDCCD", p.name, "${fmt(p.lat)}, ${fmt(p.lon)}", del) {
                d.dismiss()
                setLocation(p.lat, p.lon, p.name)
            })
        }
        d.body.addView(sheetRow("\uFF0B", "Save current location", "$placeName \u00B7 ${fmt(lat)}, ${fmt(lon)}") {
            d.dismiss()
            promptSavePlace()
        })
        d.body.addView(sheetRow("\uD83D\uDD0E", "Search for a city or ZIP", null) {
            d.dismiss()
            showCoordDialog()
        })
        d.button("Close")
        d.show()
    }

    private fun promptSavePlace() {
        val generic = placeName == "Current location" || placeName == "GPS location" || placeName == "Custom location"
        val d = SheetDialog(this).title("Save location")
        d.setOnDismissListener { updateCoordsView() }
        d.message("${fmt(lat)}, ${fmt(lon)}")
        val input = inputField("Name, e.g. Home", if (generic) "" else placeName)
        d.body.addView(input)
        fun save() {
            val name = input.text.toString().trim().ifBlank { "Place ${Places.all(this).size + 1}" }
            if (Places.add(this, Place(name, lat, lon))) {
                placeName = name
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("place_name", name).apply()
                updateCoordsView()
                d.dismiss()
            } else {
                Toast.makeText(this, "You can save up to ${Places.MAX} places", Toast.LENGTH_LONG).show()
            }
        }
        input.setOnEditorActionListener { _, _, _ -> save(); true }
        d.button("Cancel")
        d.button("Save", primary = true, dismiss = false) { save() }
        d.show()
        input.requestFocus()
    }

    // ------------------------------------------------------------ settings

    private fun showSettings() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = SheetDialog(this).title("Settings")
        val body = d.body

        body.addView(sectionLabel("Display"))
        val (rMetric, sMetric) = switchRow("Metric units", "\u00B0C, km/h, hPa, km", Units.metric(this))
        body.addView(rMetric)

        body.addView(sectionLabel("Notifications"))
        val (rAlerts, sAlerts) = switchRow("Severe weather alerts", "Tornado, flood, storm warnings and more", prefs.getBoolean("notify", false))
        val (rStorms, sStorms) = switchRow("Tropical storm alerts", "New storms, strengthening, nearby storms", prefs.getBoolean("notify_storms", false))
        val (rTemp, sTemp) = switchRow("Temperature in status bar", "Always-on notification showing the current temperature", prefs.getBoolean(TempNotifier.PREF, false))
        val (rQuiet, sQuiet) = switchRow("Quiet hours", "10 PM \u2013 7 AM (Extreme alerts still come through)", prefs.getBoolean("quiet", false))
        body.addView(rAlerts); body.addView(rStorms); body.addView(rTemp); body.addView(rQuiet)

        body.addView(sectionLabel("Alert level"))
        val sevValues = listOf(4, 3, 2)
        val (segSev, getSev) = segmented(
            listOf("Extreme", "Severe+", "Moderate+"),
            sevValues.indexOf(prefs.getInt("min_sev", 3)).let { if (it < 0) 1 else it }
        )
        body.addView(segSev)
        val (rWatch, sWatch) = switchRow("Include watches", null, prefs.getBoolean("incl_watch", true))
        val (rAdv, sAdv) = switchRow("Include advisories & statements", null, prefs.getBoolean("incl_advisory", false))
        body.addView(rWatch); body.addView(rAdv)

        body.addView(sectionLabel("Notify when a storm is within"))
        val radiusKm = listOf(400f, 800f, 1600f)
        val radiusLabels = if (Units.metric(this)) listOf("400 km", "800 km", "1,600 km")
        else listOf("250 mi", "500 mi", "1,000 mi")
        val curRadius = prefs.getFloat("storm_radius_km", 800f)
        val (segRad, getRad) = segmented(
            radiusLabels,
            radiusKm.indices.minByOrNull { Math.abs(radiusKm[it] - curRadius) } ?: 1
        )
        body.addView(segRad)

        d.button("Cancel")
        d.button("Apply", primary = true) {
            Units.setMetric(this, sMetric.isChecked)
            prefs.edit()
                .putBoolean("notify", sAlerts.isChecked)
                .putBoolean("notify_storms", sStorms.isChecked)
                .putBoolean(TempNotifier.PREF, sTemp.isChecked)
                .putBoolean("quiet", sQuiet.isChecked)
                .putInt("min_sev", sevValues[getSev()])
                .putBoolean("incl_watch", sWatch.isChecked)
                .putBoolean("incl_advisory", sAdv.isChecked)
                .putFloat("storm_radius_km", radiusKm[getRad()])
                .apply()
            val anyNotify = sAlerts.isChecked || sStorms.isChecked
            AlertScheduler.apply(this, anyNotify)
            TempNotifier.apply(this, sTemp.isChecked)
            if ((anyNotify || sTemp.isChecked) && android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF
                )
            }
            panels[currentTab].refresh(lat, lon)
            StormWidget.refreshAll(this)
        }
        d.show()
    }
}
