package com.kolnovel.reader

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.kolnovel.reader.data.KolSource
import com.kolnovel.reader.data.NovelSummary
import com.kolnovel.reader.data.SettingsStore
import com.kolnovel.reader.ui.App
import com.kolnovel.reader.ui.AppServices
import com.kolnovel.reader.ui.Screen
import com.kolnovel.reader.ui.Tab
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Draws the real screens off-screen into build/screens/ using saved pages from samples/
 * (skipped when they are missing). Chapter text is made-up filler, not the site's.
 */
class ScreenshotTest {
    private fun fakeChapter(): String {
        val filler = (1..30).joinToString("\n") { i ->
            "<p class='real'>هذه فقرة تجريبية رقم $i لاختبار شكل القارئ وتباعد الأسطر والمحاذاة في الواجهة.</p>" +
                "<p class=\"a0000000000000000000000000000000f\">علامة مخفية</p>"
        }
        return """<html><body><article>
            <div class="ts-breadcrumb"><span itemprop="itemListElement"><a href="https://kolnovel.com/">ر</a></span>
            <span itemprop="itemListElement"><a href="https://kolnovel.com/series/emperors-domination/">رواية تجريبية</a></span></div>
            <style>.a0000000000000000000000000000000f{height:0.1px;opacity:0;position:fixed}</style>
            <div class="epheader"><h1 class="entry-title">الفصل التجريبي 1</h1></div>
            <a rel="prev" href="https://kolnovel.com/x-1/">p</a><a rel="next" href="https://kolnovel.com/x-3/">n</a>
            <div id="kol_content" class="epcontent">$filler</div></article></body></html>"""
    }

    private fun client() = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url.toString()
        val body = when {
            url == "https://kolnovel.com/" -> File("samples/home.html").readText()
            url.contains("?s=") -> File("samples/search.html").readText()
            url.contains("/series/?") || url.endsWith("/series/") -> File("samples/series.html").readText()
            url.contains("/series/") -> File("samples/novel.html").readText()
            else -> fakeChapter()
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
    }.build()

    @Test
    fun screens() {
        if (!File("samples/home.html").exists()) return
        System.setProperty("user.home", File("build/home").absolutePath)
        val out = File("build/screens").apply { mkdirs() }
        val services = AppServices(KolSource(client()))
        val novel = NovelSummary("https://kolnovel.com/series/emperors-domination/", "رواية")
        val shots = listOf(
            "home" to Screen.Main(Tab.Home),
            "browse" to Screen.Main(Tab.Browse),
            "details" to Screen.Details(novel),
            "reader" to Screen.Reader(novel, "https://kolnovel.com/x-2/"),
            "settings" to Screen.Main(Tab.Settings),
            "library" to Screen.Main(Tab.Library),
        )
        for (theme in listOf("black", "white", "grey")) {
            SettingsStore.update { it.copy(theme = theme, liveBackground = false) }
            val scene = ImageComposeScene(1400, 900, Density(1f)) { App(services) }
            for ((name, screen) in shots) {
                services.nav.go(Screen.Main(Tab.Home))
                services.nav.go(screen)
                var t = 0L
                repeat(12) {
                    scene.render(t); t += 100_000_000L
                    Thread.sleep(150)
                }
                val img = scene.render(t)
                File(out, "$theme-$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            scene.close()
        }
    }
}
