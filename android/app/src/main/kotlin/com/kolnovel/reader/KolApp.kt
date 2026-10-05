package com.kolnovel.reader

import android.app.Application
import com.kolnovel.reader.data.AppDirs
import com.kolnovel.reader.data.Downloads
import com.kolnovel.reader.data.KolSource
import com.kolnovel.reader.data.WebViewCookieJar
import com.kolnovel.reader.ui.AppServices

class KolApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppDirs.init(filesDir)
        // Load the WebView cookie store on the main thread before network threads touch it.
        WebViewCookieJar.flush()
        val source = KolSource()
        services = AppServices(source)
        Downloads.init(this, source)
    }

    companion object {
        lateinit var services: AppServices
            private set
    }
}
