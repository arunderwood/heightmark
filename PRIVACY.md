# HeightMark Privacy Policy

**Last updated: 2026-07-30**

This policy covers the HeightMark Android app (package `com.bizzarosn.heightmark`), published by its maintainer. The app's source code is public at
[github.com/arunderwood/heightmark](https://github.com/arunderwood/heightmark), so every claim below can be checked against the code.

## Summary

HeightMark reads your device's precise GPS location for one purpose: to compute the elevation shown on screen. That location data stays on your device. It is not stored, not uploaded, and not shared. The app has no analytics, no advertising, no crash-reporting service, no third-party SDKs, and no Google Play services dependency.

The app does not request the `INTERNET` permission, so it cannot make network connections at all.

## What the app accesses

- **Precise location** (`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`). Elevation comes from GNSS altitude, which requires precise location — a coarse or "approximate" grant is not enough, and the app will ask you to upgrade it. Fixes come from the Android `LocationManager`'s GPS provider, plus the passive provider, which lets HeightMark reuse a fix another app already requested instead of powering the GPS radio itself.
- **Motion and pressure sensors.** While the GPS radio is duty-cycled off to save power, the significant-motion sensor and the barometer are used to detect that you have moved and that a fresh fix is warranted. Barometric pressure is also shown in the optional Details panel.

## How location is used

Each GPS fix is converted from WGS84 ellipsoid height to elevation above Mean Sea Level using Android's built-in `AltitudeConverter`, which uses geoid data stored on the device and works entirely offline. Fixes are averaged over a short rolling window to steady the displayed number.

All of this happens in memory, in the app process. Position data is never written to a file, database, or preference store. When the app process ends, the readings are gone.

If you turn on the optional **Details** panel, it displays diagnostics about the current fix — including your latitude and longitude, accuracy figures, satellite counts, and barometric pressure. That information is rendered on your screen and nowhere else.

## What leaves your device

Nothing.

HeightMark declares no `INTERNET` permission and contains no networking code, no analytics library, no advertising library, and no crash-reporting library. Its entire dependency list is AndroidX, Google's Material Components, Hilt (dependency injection), and Jetpack DataStore — none of which transmit your data. The app deliberately avoids Google Play services so that it behaves identically on standard Android and on de-googled builds such as GrapheneOS, LineageOS, CalyxOS, and /e/OS.

There are no user accounts, no sign-in, and no way to contact you through the app.

## What is stored on your device

Exactly two settings, saved locally through Jetpack DataStore:

- your unit preference (metric or imperial), and
- whether the Details panel is shown.

That is the complete list. No location, no history, no identifiers.

## Backup

HeightMark participates in standard Android backup. The settings file described above (`datastore/settings.preferences_pb`) is included in both cloud backup and device-to-device transfer — see [`app/src/main/res/xml/data_extraction_rules.xml`](app/src/main/res/xml/data_extraction_rules.xml).

In practice this means that if you have Android's backup feature enabled, those two preference values may be copied to your own backup — for most users, their Google account — and restored when you set up a new device. That backup is handled by Android and governed by your device's backup settings and your backup provider's privacy policy, not by HeightMark. No location data is in that file, because the app never stores any.

## Third parties

HeightMark integrates no third-party services and sends data to no one.

The app itself contains no crash-reporting code. Separately, if you installed it from the Google Play Store, that store is Google's service and is covered by Google's privacy policy; any crash or performance data Android itself reports to a developer console is controlled by your device's diagnostics settings, not by anything in the app.

## Children

HeightMark collects no personal information from anyone, of any age. There is nothing to solicit, transmit, or delete on a server, because there is no server.

## Removing your data

Uninstalling the app removes its local settings from your device. If Android backup is enabled, a copy of those settings may persist in your backup until your backup provider removes it under its own retention rules; you can delete app backup data through your device's backup settings.

There is no other data to remove, and no request to send anyone — the maintainer has no copy of anything.

## Changes to this policy

If this policy changes, the updated version will be published in the app's repository, with the change visible in the commit history.

## Contact

Questions about privacy in HeightMark go to the project's issue tracker:
[github.com/arunderwood/heightmark/issues](https://github.com/arunderwood/heightmark/issues)
