# Plume Launcher

*Light as a feather.* A minimal, ad-free home screen for Android TV and Google TV.

Your apps, a Continue Watching row, and nothing else. No ads, no recommendations, no account, no tracking.

## Why Plume

Measured on a Hisense Google TV (Android 12, 1.8 GB RAM):

| | Plume | Projectivy | Google TV launcher |
|---|---|---|---|
| Download | **5.1 MB** (under 1 MB without the screensaver art) | ~10–14 MB | 25 MB |
| RAM while running | **~45–60 MB** | 65–70 MB | ~93 MB + 22 MB helper |
| Background services | none extra | 2 | always-on helper |
| Warm start (back from an app) | **~95 ms** | | |

No third-party libraries: plain Android views only.

## Features

- **Apps row**: mono logos, rearrange by holding OK, sort manually or by most used
- **Continue Watching**: Android TV's Watch Next plus apps' own "Continue Watching" channels (e.g. Stremio); hide items by holding OK
- **HDMI devices as tiles**: a console on HDMI (it names itself over CEC) gets its own tile
- **Light, dark or automatic theme**, each with its own wallpaper; add your own wallpapers
- **Gallery screensaver**: public-domain landscape paintings, all 16:9 so they fill the screen
- **Weather** under the clock (optional, off by default)
- **The Home button opens Plume directly**, even on Google TV, where the system launcher can't be replaced. Holding Home still opens the Google TV dashboard.

## Install

1. Download `Plume-<version>.apk` from [Releases](../../releases).
2. Sideload it: use the *Downloader* app, a USB stick with a file manager, or `adb install Plume-<version>.apk`.
3. Open **Plume**. Its settings open on first launch:
   - **Home button**: press OK, then switch on *Plume: Home button* in Accessibility (Android shows a standard warning for every accessibility service; Plume only listens for the Home key and can't read the screen).
   - **Default launcher**: press OK and pick Plume.
   - Allow the TV listings permission so Continue Watching can fill in.

On Android 13 and newer, sideloaded apps are blocked from Accessibility at first. Open *Settings > Apps > Plume*, choose *Allow restricted settings*, and then turn it on.

### Optional: one adb command

```
adb shell pm grant dev.plume android.permission.WRITE_SECURE_SETTINGS
```

This lets Plume switch the Gallery screensaver on from its own settings, and turn its Home button back on by itself if the app is ever force-stopped. Everything else works without it.

### Your own wallpapers

Copy `.jpg`, `.png` or `.webp` files into `Android/data/dev.plume/files/wallpapers/`, for example with `adb push`. They appear in *Light/Dark mode wallpaper*.

## Privacy

Plume has no analytics and no account. The only network requests are:
- poster images for your Continue Watching items
- **only if you turn Weather on**: your approximate location from your IP address ([ipwho.is](https://ipwho.is)), and the forecast from [Open-Meteo](https://open-meteo.com)

## Build

```
./gradlew assembleRelease
```

Without a `keystore.properties`, release builds are signed with your debug key, which is fine for sideloading your own build.

## Credits

- **Screensaver paintings** (public domain, scans via Wikimedia Commons): Frederic Edwin Church, *The Heart of the Andes*, *The Icebergs*, *Chimborazo*, *Cotopaxi*; Ivan Shishkin, *Rye*; Jasper Francis Cropsey, *Autumn on the Hudson River*; Martin Johnson Heade, *Thunder Storm on Narragansett Bay*
- **Wallpapers**: "Canada" by Thomas Curryer ([Unsplash](https://unsplash.com/@tawc20)), CC0
- **App logos**: [Simple Icons](https://simpleicons.org), CC0. Brand marks belong to their owners.

## License

[MIT](LICENSE)
