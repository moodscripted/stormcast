# StormCast 🌀

A Kotlin Android app for US weather with NOAA data — no API keys required.

## Features

- **Forecast** — NWS point forecast (`api.weather.gov`) for your GPS location or manual coordinates
- **Alerts** — active NWS alerts, color-coded by severity (tornado / hurricane / severe weather warnings included)
- **Radar** — animated radar map (RainViewer tiles + Leaflet in a WebView) with Play/Pause
- **Cyclones** — active tropical cyclones from NOAA NHC with full advisory data and an in-app cone & track map rendered from NHC GIS data

## Build

Requires JDK 17.

### Android Studio (easiest)

1. Open this folder in Android Studio (Hedgehog or newer).
2. Let it create the Gradle wrapper (or use its bundled Gradle ≥ 8.7) when prompted.
3. Connect a phone or start an emulator and press **Run ▶**.

### Command line

```bash
gradle wrapper --gradle-version 8.7   # creates gradlew (needs Gradle >= 8.7 installed)
./gradlew assembleDebug               # APK at app/build/outputs/apk/debug/
./gradlew installDebug                # install on a connected device
```

## Usage

First launch defaults to Miami, FL. Tap **📍 GPS** (grant the permission) or **Set…** to
enter coordinates anywhere in the US. The location is saved between launches.

## Tech

- Kotlin, no external UI framework — UI built programmatically, `org.json` for parsing,
  coroutines for networking, WebView + Leaflet for maps.
- Data: api.weather.gov, nhc.noaa.gov, RainViewer free tile service.

## License

MIT — do whatever you like.
