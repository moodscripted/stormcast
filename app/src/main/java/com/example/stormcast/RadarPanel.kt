package com.example.stormcast

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.WebView

class RadarPanel(private val ctx: Context) : Panel {

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
        web.setBackgroundColor(0xFF0B1220.toInt())
        web.clearCache(true)
        web.loadDataWithBaseURL(
            "https://api.rainviewer.com/",
            html(lat, lon),
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun html(lat: Double, lon: Double): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>__LEAFLET_CSS__</style>
        <style>html,body,#map{margin:0;padding:0;height:100%;background:#0B1220}</style>
        <style>#status{position:absolute;top:10px;left:10px;z-index:1000;background:rgba(10,16,30,.88);color:#cbd5e1;padding:6px 12px;border-radius:8px;font:13px sans-serif}</style>
        <script>window.onerror = function(msg) { var s = document.getElementById('status'); if (s) s.textContent = 'Map error: ' + msg; };</script>
        <script>__LEAFLET_JS__</script>
        </head>
        <body>
        <div id="map"></div>
        <div id="status">Loading radar...</div>
        <script>
        var LAT = __LAT__;
        var LON = __LON__;
        function setStatus(t) {
          var s = document.getElementById('status');
          if (t === null) { s.style.display = 'none'; }
          else { s.style.display = 'block'; s.textContent = t; }
        }
        var map = L.map('map', {minZoom: 3, maxZoom: 16}).setView([LAT, LON], 7);
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
          setStatus('Some basemap tiles failed to load' + (m ? ' (zoom ' + m[1] + ')' : '') + ' - tap Refresh to retry');
        });
        L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}', {
          attribution: 'Tiles &copy; Esri &mdash; Esri, HERE, Garmin, FAO, NOAA, USGS',
          maxZoom: 16
        }).addTo(map);
        L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/{z}/{y}/{x}', {
          maxZoom: 16, opacity: 0.9
        }).addTo(map);
        L.circleMarker([LAT, LON], {radius: 7, color: '#FFFFFF', weight: 2, fillColor: '#60A5FA', fillOpacity: 1}).addTo(map);
        fetch('https://api.rainviewer.com/public/weather-maps.json')
          .then(function(r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
          })
          .then(function(d) {
            var frames = d.radar.past.slice(-12);
            if (!frames.length) { setStatus('No radar frames available'); return; }
            var layers = frames.map(function(f) {
              return L.tileLayer(d.host + f.path + '/256/{z}/{x}/{y}/4/1_1.png', {opacity: 0.65});
            });
            var i = -1, timer = null, playing = false;
            function step() {
              if (i >= 0) map.removeLayer(layers[i]);
              i = (i + 1) % layers.length;
              map.addLayer(layers[i]);
            }
            step();
            var Ctrl = L.Control.extend({
              onAdd: function() {
                var b = L.DomUtil.create('button');
                b.innerHTML = '&#9654; Play';
                b.style.cssText = 'padding:10px 20px;font-size:14px;font-weight:600;color:#fff;background:rgba(13,25,48,.92);border:1px solid rgba(255,255,255,.25);border-radius:999px;cursor:pointer;box-shadow:0 2px 10px rgba(0,0,0,.45);';
                L.DomEvent.disableClickPropagation(b);
                L.DomEvent.on(b, 'click', function() {
                  playing = !playing;
                  b.innerHTML = playing ? '&#10074;&#10074; Pause' : '&#9654; Play';
                  if (playing) timer = setInterval(step, 600);
                  else clearInterval(timer);
                });
                return b;
              }
            });
            map.addControl(new Ctrl({position: 'bottomleft'}));
          })
          .catch(function(e) { setStatus('Radar unavailable: ' + e.message); });
        </script>
        </body>
        </html>
        """.trimIndent()
            .replace("__LEAFLET_CSS__", leafletCss)
            .replace("__LEAFLET_JS__", leafletJs)
            .replace("__LAT__", fmt(lat))
            .replace("__LON__", fmt(lon))
    }
}
