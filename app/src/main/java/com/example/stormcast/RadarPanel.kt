package com.example.stormcast

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.WebView

class RadarPanel(private val ctx: Context) : Panel {

    private val web = WebView(ctx)

    override val root: View = web

    @SuppressLint("SetJavaScriptEnabled")
    override fun refresh(lat: Double, lon: Double) {
        web.settings.javaScriptEnabled = true
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
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
        <style>html,body,#map{margin:0;padding:0;height:100%;background:#0B1220}</style>
        </head>
        <body>
        <div id="map"></div>
        <script>
        var LAT = __LAT__;
        var LON = __LON__;
        var map = L.map('map').setView([LAT, LON], 7);
        L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
          attribution: '&copy; OpenStreetMap contributors &copy; CARTO',
          subdomains: 'abcd', maxZoom: 19
        }).addTo(map);
        L.circleMarker([LAT, LON], {radius: 7, color: '#FFFFFF', weight: 2, fillColor: '#60A5FA', fillOpacity: 1}).addTo(map);
        fetch('/public/weather-maps.json').then(function(r){ return r.json(); }).then(function(d) {
          var frames = d.radar.past.slice(-12);
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
        });
        </script>
        </body>
        </html>
        """.trimIndent()
            .replace("__LAT__", fmt(lat))
            .replace("__LON__", fmt(lon))
    }
}
