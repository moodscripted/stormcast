package com.example.stormcast

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "stormcast"
        private const val REQ_PERMS = 101
    }

    private lateinit var coordsView: TextView
    private lateinit var content: FrameLayout
    private lateinit var tabs: Array<Button>
    private val panels = ArrayList<Panel>()
    private var currentTab = -1
    private var lat = 25.7617
    private var lon = -80.1918

    private val tabNames = arrayOf("Forecast", "Alerts", "Radar", "Cyclones")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        lat = prefs.getFloat("lat", lat.toFloat()).toDouble()
        lon = prefs.getFloat("lon", lon.toFloat()).toDouble()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- Header ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0D47A1.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(16), dp(14), dp(8), dp(14))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val gpsBtn = Button(this).apply { text = "📍 GPS"; setOnClickListener { useGps() } }
        val setBtn = Button(this).apply { text = "Set…"; setOnClickListener { showCoordDialog() } }
        header.addView(title)
        header.addView(gpsBtn)
        header.addView(setBtn)

        coordsView = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFB3CDEB.toInt())
            setBackgroundColor(0xFF0D47A1.toInt())
            setPadding(dp(16), 0, dp(16), dp(10))
        }

        // ---- Tabs ----
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs = Array(tabNames.size) { i ->
            Button(this).apply {
                text = tabNames[i]
                isAllCaps = false
                setOnClickListener { selectTab(i) }
            }
        }
        for (b in tabs) {
            b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            tabRow.addView(b)
        }

        // ---- Content ----
        content = FrameLayout(this)

        root.addView(header)
        root.addView(coordsView)
        root.addView(tabRow)
        root.addView(
            content,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)

        panels.add(ForecastPanel(this))
        panels.add(AlertsPanel(this))
        panels.add(RadarPanel(this))
        panels.add(HurricanePanel(this))

        updateCoordsView()
        selectTab(0)
    }

    private fun selectTab(i: Int) {
        currentTab = i
        for (j in tabs.indices) tabs[j].isEnabled = j != i
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
        coordsView.text = "Location: ${fmt(lat)}, ${fmt(lon)}"
    }

    private fun setLocation(la: Double, lo: Double) {
        lat = la
        lon = lo
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("lat", la.toFloat())
            .putFloat("lon", lo.toFloat())
            .apply()
        updateCoordsView()
        panels[currentTab].refresh(lat, lon)
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
                setLocation(loc.latitude, loc.longitude)
            } else {
                Toast.makeText(
                    this,
                    "No last known location. Enable location services or use Set…",
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
        if (requestCode == REQ_PERMS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                useGps()
            } else {
                Toast.makeText(this, "Location denied — use Set… to enter coordinates", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showCoordDialog() {
        val input = EditText(this).apply {
            hint = "e.g. 28.5383, -81.3792"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            text = android.text.Editable.Factory.getInstance().newEditable("${fmt(lat)}, ${fmt(lon)}")
        }
        AlertDialog.Builder(this)
            .setTitle("Set location (latitude, longitude)")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val parts = input.text.toString().split(",")
                if (parts.size == 2) {
                    val la = parts[0].trim().toDoubleOrNull()
                    val lo = parts[1].trim().toDoubleOrNull()
                    if (la != null && lo != null && Math.abs(la) <= 90.0 && Math.abs(lo) <= 180.0) {
                        setLocation(la, lo)
                        return@setPositiveButton
                    }
                }
                Toast.makeText(this, "Invalid coordinates", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
