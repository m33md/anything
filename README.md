# Convx for Windows

A desktop build of [Convx](https://github.com/cosmictaserdev-creator/convx), laid out like
Spotify's desktop app: library sidebar on the left, pages in the middle, the player bar at
the bottom and an optional right panel for now playing, lyrics and the queue.

It is written with Compose Multiplatform and compiles the phone app's own YouTube Music
(`innertube/`) and lyrics (`lyrics/`) code unchanged. Audio is decoded with FFmpeg.

## Download

Every push builds Windows installers on GitHub Actions. Open the latest run of
**Desktop build** under the Actions tab and download `Convx-windows-installers`
(`.msi` or `.exe`) or `Convx-windows-portable` (unzip and run `Convx.exe`).

## Build it yourself

Needs JDK 21.

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

GPL-3.0, like Convx. See `LICENSE` and `NOTICE`.
