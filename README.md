# Kol Novel Reader

An unofficial Windows reader for the Arabic novels on [kolnovel.com](https://kolnovel.com/) (ملوك الروايات),
built the same way as Olympus Reader: Kotlin + Compose Desktop, OkHttp + Jsoup, shipped as a folder with
`app\*.jar`, a bundled Java runtime and `.bat` launchers.

It reads the site live (home, series list with the site's filters, search, novel pages with every chapter,
chapter text) and adds a library, favourites, history, reading progress, offline chapters, and themes
(black, greys, white, paper, navy, wine) with a configurable accent colour and frosted-glass cards.

For personal reading only. The novels and translations belong to the site and its translators.

## Layout

- `data/KolSource.kt` – fetches and parses the site (Themesia "lightnovel" theme; the WordPress REST API is closed).
  Chapters hide watermark text in elements whose random class names a `<style>` makes invisible; those are dropped.
- `data/Stores.kt` – settings, library and saved chapters under `%APPDATA%\KolNovelReader`.
- `ui/` – screens, theme and the glass components.

## Building

Google's Maven repository is not reachable from the build machine this was made on, so the Compose jars are not
resolved by Gradle. Put the jars from an Olympus Reader install (`OlympusReader\app\*.jar`, minus
`OlympusReaderDesktop-*.jar`) into `libs/`, then:

```
gradle test                 # parser tests; they use pages saved into samples/ and skip without them
packaging/assemble.sh       # dist/KolNovelReader + dist/KolNovelReader.zip
```

Put `dist/KolNovelReader` next to the `OlympusReader` folder; the launcher copies Java from it on first start.
