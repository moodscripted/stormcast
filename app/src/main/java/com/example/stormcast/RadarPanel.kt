package com.example.stormcast

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class RadarPanel(private val ctx: Context) : Panel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val web = WebView(ctx)

    private val leafletCss: String by lazy {
        ctx.assets.open("leaflet.css").bufferedReader().use { it.readText() }
    }

    private val leafletJs: String by lazy {
        ctx.assets.open("leaflet.js").bufferedReader().use { it.readText() }
    }

    override val root: View = web

    @SuppressLint("SetJavaScriptEnabled")
    override fun refresh(lat: Double, lon: Double) {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.setBackgroundColor(0xFF0B1220.toInt())
        job?.cancel()
        job = scope.launch {
            // Active storms are drawn as a toggleable overlay; failure just means none are shown.
            val storms = try { fetchStorms() } catch (e: Exception) { JSONArray() }
            web.clearCache(true)
            web.loadDataWithBaseURL(
                "https://www.openstreetmap.org/",
                html(lat, lon, storms),
                "text/html",
                "UTF-8",
                null
            )
        }
    }

    private suspend fun fetchStorms(): JSONArray {
        val data = Api.getJson("https://www.nhc.noaa.gov/CurrentStorms.json")
        val src = data.optJSONArray("activeStorms") ?: return JSONArray()
        val out = JSONArray()
        for (i in 0 until src.length()) {
            val s = src.getJSONObject(i)
            val la = s.optDouble("latitudeNumeric", Double.NaN)
            val lo = s.optDouble("longitudeNumeric", Double.NaN)
            if (la.isNaN() || lo.isNaN()) continue
            out.put(
                JSONObject()
                    .put("name", s.optString("name"))
                    .put("cls", s.optString("classification"))
                    .put("wind", s.optString("intensity"))
                    .put("lat", la)
                    .put("lon", lo)
            )
        }
        return out
    }

    private fun html(lat: Double, lon: Double, storms: JSONArray): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
        <style>__LEAFLET_CSS__</style>
        <style>html,body,#map{margin:0;padding:0;height:100%;background:#0B1220}</style>
        <style>#status{position:absolute;top:10px;left:56px;z-index:1000;background:rgba(10,16,30,.88);color:#cbd5e1;padding:6px 12px;border-radius:8px;font:13px sans-serif}</style>
        <style>
        .stormtip{background:rgba(10,16,30,.92);color:#fff;border:1px solid #EF4444;font:600 12px sans-serif;border-radius:6px}
        .chipbar{display:flex;gap:6px}
        .chip{padding:8px 12px;font:600 13px sans-serif;color:#fff;background:rgba(13,25,48,.92);border:1px solid rgba(255,255,255,.25);border-radius:999px;box-shadow:0 2px 10px rgba(0,0,0,.45)}
        .chip.on{background:#2563EB;border-color:#60A5FA}
        .tl{display:flex;align-items:center;gap:10px;padding:8px 14px;background:rgba(13,25,48,.92);border:1px solid rgba(255,255,255,.25);border-radius:999px;box-shadow:0 2px 10px rgba(0,0,0,.45);width:min(88vw,440px);box-sizing:border-box}
        .tl button{all:unset;color:#fff;font:600 14px sans-serif;min-width:64px}
        .tl input{flex:1;min-width:60px}
        .tl span{color:#cbd5e1;font:12px sans-serif;min-width:86px;text-align:right}
        </style>
        <script>window.onerror = function(msg) { var s = document.getElementById('status'); if (s) { s.style.display='block'; s.textContent = 'Map error: ' + msg; } };</script>
        <script>__LEAFLET_JS__</script>
        </head>
        <body>
        <div id="map"></div>
        <div id="status">Loading radar...</div>
        <script>
        var LAT = __LAT__;
        var LON = __LON__;
        var STORMS = __STORMS__;
        function setStatus(t) {
          var s = document.getElementById('status');
          if (t === null) { s.style.display = 'none'; }
          else { s.style.display = 'block'; s.textContent = t; }
        }
        // Radar tiles only exist up to zoom 7 (they are upscaled beyond that).
        var map = L.map('map', {minZoom: 3, maxZoom: 12}).setView([LAT, LON], 7);
        map.whenReady(function() {
          setTimeout(function() {
            if (map.getSize().x > 0) setStatus(null);
            else setStatus('Map area did not size correctly');
          }, 0);
        });
        var tileErrShown = false;
        map.on('tileerror', function(e) {
          if (tileErrShown) return;
          tileErrShown = true;
          var m = (e.tile && e.tile.src || '').match(/tile\/(\d+)\//);
          setStatus('Some basemap tiles failed to load' + (m ? ' (zoom ' + m[1] + ')' : '') + ' - pull down to refresh');
        });
        L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}', {
          attribution: 'Tiles &copy; Esri &mdash; Esri, HERE, Garmin, FAO, NOAA, USGS',
          maxNativeZoom: 16, maxZoom: 12
        }).addTo(map);
        L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/{z}/{y}/{x}', {
          maxNativeZoom: 16, maxZoom: 12, opacity: 0.9
        }).addTo(map);
        L.circleMarker([LAT, LON], {radius: 7, color: '#FFFFFF', weight: 2, fillColor: '#60A5FA', fillOpacity: 1}).addTo(map);

        // ---- Overlays: storms (on by default) and severe alerts (loaded on demand) ----
        var stormLayer = L.layerGroup();
        STORMS.forEach(function(s) {
          var label = s.name + (s.wind ? ' · ' + s.wind + ' kt' : '');
          L.circleMarker([s.lat, s.lon], {radius: 8, color: '#FFFFFF', weight: 2, fillColor: '#EF4444', fillOpacity: 1})
            .bindTooltip(label, {permanent: true, direction: 'right', offset: [10, 0], className: 'stormtip'})
            .addTo(stormLayer);
        });
        stormLayer.addTo(map);

        var alertLayer = L.layerGroup();
        var alertsLoaded = false;
        function loadAlerts() {
          if (alertsLoaded) return;
          alertsLoaded = true;
          setStatus('Loading alerts...');
          fetch('https://api.weather.gov/alerts/active?status=actual&severity=Severe,Extreme')
            .then(function(r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
            .then(function(d) {
              (d.features || []).forEach(function(f) {
                if (!f.geometry) return;
                var c = f.properties.severity === 'Extreme' ? '#EF4444' : '#F59E0B';
                L.geoJSON(f.geometry, {style: {color: c, weight: 2, fillColor: c, fillOpacity: 0.15}})
                  .bindTooltip(f.properties.event, {sticky: true}).addTo(alertLayer);
              });
              setStatus(null);
            })
            .catch(function(e) { alertsLoaded = false; setStatus('Alerts unavailable: ' + e.message); });
        }

        var Chips = L.Control.extend({
          onAdd: function() {
            var bar = L.DomUtil.create('div', 'chipbar');
            function chip(text, on, handler) {
              var b = L.DomUtil.create('button', 'chip' + (on ? ' on' : ''), bar);
              b.innerHTML = text;
              L.DomEvent.on(b, 'click', function() { handler(b); });
              return b;
            }
            chip('&#127744; Storms', true, function(b) {
              var on = !map.hasLayer(stormLayer);
              if (on) map.addLayer(stormLayer); else map.removeLayer(stormLayer);
              b.className = 'chip' + (on ? ' on' : '');
            });
            chip('&#9888; Alerts', false, function(b) {
              var on = !map.hasLayer(alertLayer);
              if (on) { map.addLayer(alertLayer); loadAlerts(); } else map.removeLayer(alertLayer);
              b.className = 'chip' + (on ? ' on' : '');
            });
            chip('&#9678; Me', false, function() { map.setView([LAT, LON], 8); });
            L.DomEvent.disableClickPropagation(bar);
            return bar;
          }
        });
        map.addControl(new Chips({position: 'topright'}));

        // ---- Radar frames with a scrubbable timeline ----
        fetch('https://api.rainviewer.com/public/weather-maps.json')
          .then(function(r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
          })
          .then(function(d) {
            var past = d.radar.past.slice(-12);
            var future = d.radar.nowcast || [];
            var frames = past.concat(future);
            var nPast = past.length;
            if (!frames.length) { setStatus('No radar frames available'); return; }
            var layers = frames.map(function(f) {
              return L.tileLayer(d.host + f.path + '/256/{z}/{x}/{y}/4/1_1.png',
                {opacity: 0.65, maxNativeZoom: 7, maxZoom: 12, zIndex: 5});
            });
            var cur = -1, timer = null, playing = false;
            var btn, range, lab;
            function show(i) {
              if (cur >= 0) map.removeLayer(layers[cur]);
              cur = i;
              map.addLayer(layers[cur]);
              if (range) range.value = cur;
              if (lab) {
                var t = new Date(frames[cur].time * 1000)
                  .toLocaleTimeString([], {hour: 'numeric', minute: '2-digit'});
                lab.textContent = t + (cur >= nPast ? ' (forecast)' : '');
              }
            }
            function stop() { playing = false; clearInterval(timer); if (btn) btn.innerHTML = '&#9654; Play'; }
            var Tl = L.Control.extend({
              onAdd: function() {
                var box = L.DomUtil.create('div', 'tl');
                btn = L.DomUtil.create('button', '', box);
                btn.innerHTML = '&#9654; Play';
                range = L.DomUtil.create('input', '', box);
                range.type = 'range';
                range.min = 0;
                range.max = frames.length - 1;
                range.value = Math.max(nPast - 1, 0);
                lab = L.DomUtil.create('span', '', box);
                L.DomEvent.disableClickPropagation(box);
                L.DomEvent.on(box, 'touchstart mousedown pointerdown', L.DomEvent.stopPropagation);
                L.DomEvent.on(btn, 'click', function() {
                  if (playing) { stop(); return; }
                  playing = true;
                  btn.innerHTML = '&#10074;&#10074; Pause';
                  timer = setInterval(function() { show((cur + 1) % frames.length); }, 600);
                });
                L.DomEvent.on(range, 'input', function() { stop(); show(parseInt(range.value, 10)); });
                return box;
              }
            });
            map.addControl(new Tl({position: 'bottomleft'}));
            // Infrared satellite (latest frame) as a toggle: best for watching storms over open water.
            var sat = (d.satellite && d.satellite.infrared) || [];
            if (sat.length) {
              var satLayer = L.tileLayer(d.host + sat[sat.length - 1].path + '/256/{z}/{x}/{y}/0/0_0.png',
                {opacity: 0.7, maxNativeZoom: 6, maxZoom: 12, zIndex: 3});
              var SatCtl = L.Control.extend({
                onAdd: function() {
                  var bar = L.DomUtil.create('div', 'chipbar');
                  var b = L.DomUtil.create('button', 'chip', bar);
                  b.innerHTML = '&#128752; Satellite';
                  L.DomEvent.on(b, 'click', function() {
                    var on = !map.hasLayer(satLayer);
                    if (on) map.addLayer(satLayer); else map.removeLayer(satLayer);
                    b.className = 'chip' + (on ? ' on' : '');
                  });
                  L.DomEvent.disableClickPropagation(bar);
                  return bar;
                }
              });
              map.addControl(new SatCtl({position: 'topright'}));
            }
            show(Math.max(nPast - 1, 0));
          })
          .catch(function(e) { setStatus('Radar unavailable: ' + e.message); });
        </script>
        </body>
        </html>
        """.trimIndent()
            .replace("__LEAFLET_CSS__", leafletCss)
            .replace("__LEAFLET_JS__", leafletJs)
            .replace("__STORMS__", storms.toString())
            .replace("__LAT__", fmt(lat))
            .replace("__LON__", fmt(lon))
    }
}
