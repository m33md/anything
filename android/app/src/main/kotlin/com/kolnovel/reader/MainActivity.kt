package com.kolnovel.reader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kolnovel.reader.data.Downloads
import com.kolnovel.reader.data.NovelSummary
import com.kolnovel.reader.data.SettingsStore
import com.kolnovel.reader.data.WebViewCookieJar
import com.kolnovel.reader.ui.App
import com.kolnovel.reader.ui.Screen
import com.kolnovel.reader.ui.Tab

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val services = KolApp.services
        services.askNotifications = {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
        services.systemBars = { lightBackground, keepOn, immersive ->
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = lightBackground
            controller.isAppearanceLightNavigationBars = lightBackground
            if (immersive) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
            if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        handleIntent(intent)
        setContent { App(services) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val nav = KolApp.services.nav
        when {
            intent?.getStringExtra(EXTRA_OPEN) == OPEN_DOWNLOADS -> nav.go(Screen.Main(Tab.Downloads))
            // A kolnovel.com novel link opens its page in the app (also used by the automated test).
            intent?.getStringExtra(EXTRA_NOVEL) != null ->
                nav.go(Screen.Details(NovelSummary(intent.getStringExtra(EXTRA_NOVEL)!!, "")))
        }
    }

    override fun onResume() {
        super.onResume()
        Downloads.resumeIfPending()
    }

    override fun onPause() {
        WebViewCookieJar.flush()
        super.onPause()
    }

    /** In the reader, the volume buttons scroll the chapter (can be turned off in the settings). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val scroll = KolApp.services.readerVolume
        if (scroll != null && SettingsStore.settings.readerVolumeKeys &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) scroll(event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_DOWNLOADS = "downloads"
        const val EXTRA_NOVEL = "novel"
    }
}
