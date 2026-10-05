package com.kolnovel.reader.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kolnovel.reader.data.Downloads
import com.kolnovel.reader.data.KolSource
import com.kolnovel.reader.data.WebViewCookieJar

/**
 * The site in a small browser. When Cloudflare asks "are you human", the user passes it here once;
 * the cookie it leaves is shared with the app's own requests (same browser identity), so the app works again.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VerifyScreen() {
    val services = LocalServices.current
    val colors = LocalAppColors.current
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp).glass(colors, RoundedCornerShape(16.dp), strong = true).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") { services.nav.back() }
            Text(
                "أكمل التحقق إن ظهر، وعندما تظهر صفحة الموقع اضغط \"تم\".",
                color = colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            AccentButton("تم", Icons.Filled.Check, {
                WebViewCookieJar.flush()
                if (Downloads.notice == KolSource.CLOUDFLARE_MESSAGE) Downloads.resume()
                services.nav.back()
            })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Same browser identity as the app's requests, so the site's check cookie is valid for both.
                        settings.userAgentString = KolSource.USER_AGENT
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = WebViewClient()
                        loadUrl(KolSource.BASE_URL + "/")
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.destroy() },
            )
        }
    }
}
