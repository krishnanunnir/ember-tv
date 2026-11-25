<p align="center"><img src="docs/logo/banner.svg" width="480" alt="Ember"></p>

# Ember

A small, calm home screen for Android TV and Google TV, built for the remote.

- A clock, a **Favourites** row and **All apps**, as 16:9 banners.
- Hold **OK** on an app to add it to Favourites, move it, open App info or uninstall it.
- No ads, no recommendations, no network access, no tracking.

## Install

Build it (below) and install the APK with `adb install`, then pick Ember as the home app.

Google TV always opens its own home screen when you press Home, even when another home app is the default. To make
Home open Ember, turn on Ember's accessibility service in **Settings → System → Accessibility → Ember**. It only
listens for the stock home screen opening and reads nothing else. From a computer:

```bash
adb shell settings put secure enabled_accessibility_services dev.ember/dev.ember.HomeWatcher
```

(That replaces any other accessibility services you have on, such as Projectivy's.)

## Build

```bash
JAVA_HOME=/path/to/jdk-17-or-newer ./gradlew assembleDebug
```

## Licence

MIT, see [LICENSE](LICENSE). The background picture is from [Dracula Theme](https://github.com/dracula/wallpaper),
also MIT, see [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt).
