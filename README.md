# Convx for Windows

An unofficial Windows port of [Convx](https://github.com/cosmictaserdev-creator/convx), the
Android music app by [Aryan (Cosmic Taser)](https://github.com/cosmictaserdev-creator). It is not
made or endorsed by Convx's author.

It is laid out like the phone app's tablet view, sized for a PC:

- Liquid glass on true black, in Convx's red, as on the phone.
- A glass top bar in the window's title area, holding back and forward, Home, search with
  Explore, Library, your profile and the window buttons.
- A side panel with your library, and a glass now-playing bar at the bottom.
- The full-screen player has the phone's lyrics styles. Flamingo is the default, with
  word- and letter-synced lyrics.

It is written with Compose Multiplatform. It compiles the phone app's own YouTube Music
(`innertube/`) and lyrics (`lyrics/`) code. Audio is decoded with FFmpeg.

## Download

Get the latest build from the
[desktop-latest release](https://github.com/m33md/anything/releases/tag/desktop-latest) and run
`Convx-Setup-<version>.exe`. If Windows says it protected your PC, click **More info**, then
**Run anyway**. The app updates itself from the same page (Settings > Help & About > App updates).

## Build it yourself

You need JDK 21.

```
cd desktop
./gradlew run                 # start the app
./gradlew packageMsi          # Windows installer in build/compose/binaries/main/msi
```

## Where things live

| Path | What |
| --- | --- |
| `%APPDATA%\Convx` | Library (likes, playlists, history) and settings |
| `%LOCALAPPDATA%\Convx` | Song and artwork cache, logs |

## License

GPL-3.0, like Convx. This program is free software and comes with no warranty. See `LICENSE`
and `NOTICE` for the full terms and the third-party code it includes.
