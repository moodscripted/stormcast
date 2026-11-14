# StormCast 🌀

A Kotlin Android app for US weather with NOAA data — no API keys required.

## Features

- **Forecast** — NWS point forecast (`api.weather.gov`) for your GPS location or manual coordinates
- **Alerts** — active NWS alerts, color-coded by severity (tornado / hurricane / severe weather warnings included)
- **Radar** — animated radar map (RainViewer tiles + Leaflet in a WebView) with Play/Pause
- **Cyclones** — active tropical cyclones from NOAA NHC with full advisory data and an in-app cone & track map rendered from NHC GIS data
- **Sky** — moon phase, planets visible tonight, International Space Station passes and upcoming eclipses for your location

## What's new in 1.6.0

- **Switch between locations:** saved places now show as a row of chips under the header, each with its current temperature. Tap one to switch, long-press to manage or delete. The **＋** chip saves the current location (or searches for a new one). Up to 8 places

## What's new in 1.5.1

- **Temperature in status bar:** optional always-on notification (Settings > Notifications) showing the current temperature as its status-bar icon, with conditions, feels-like, wind and humidity when pulled down. Updates about every 15 minutes and when you change location or units

## What's new in 1.5.0

- **New Sky tab:**
  - Moon phase with illumination, age, rise/set and the next four phases
  - Planets that are well placed tonight, with brightness, direction, height and best time
  - ISS passes over the next 10 days, showing only the part you can see (dark sky, station in sunlight)
  - Next lunar eclipse, next solar eclipse from your spot and next one anywhere, plus the next you can actually see
- Comets are not included: there is no dependable free feed of which ones are visible
- Sky maths: [Astronomy Engine](https://github.com/cosinekitty/astronomy) (MIT, see `ASTRONOMY_ENGINE_LICENSE.txt`) and an SGP4 port; ISS orbit data from CelesTrak

## What's new in 1.4.2

- **Set location** now takes a city and state (e.g. `Orlando, FL`) or a ZIP code, with a results list when several places match
- All pop-ups (set location, locations, save place, settings) share one rounded, theme-aware style; settings use switches and pill pickers

## What's new in 1.4.0

- **Forecast:** severe storm risk (NOAA SPC Day 1, with tornado chance), air quality with pollutants and pollen, tides and waves for coastal locations
- **Notifications:** tropical storm alerts (new storm, strengthening, within a distance you pick), plus alert level, watches/advisories filters and quiet hours
- **Radar:** infrared satellite layer toggle
- **Cyclones:** NHC tropical-storm-wind arrival zones for your location (when NHC publishes them)
- **Widget:** large size with high/low, wind, humidity, next rain, 6-hour strip, nearest storm and up to 3 alerts; small size shows feels-like
- New app icon and widget preview; GitHub Actions workflow builds a debug APK on every push

## What's new in 1.3.0

- **Forecast:** current conditions, hourly view, sunrise/sunset/UV (Open-Meteo), 24 h rain-chance chart and "next rain" line
- **Cyclones:** distance and closest forecast position relative to you, watch/warning lines on the cone map, 7-day tropical outlook
- **Radar:** scrubbable timeline incl. nowcast frames, storm and severe-alert overlays, "Me" button
- **Alerts:** optional background notifications for Severe/Extreme alerts at your current and saved locations
- **Saved locations**, **metric/imperial units**, **pull-to-refresh**, offline cache of last results, and a **home-screen widget**

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
