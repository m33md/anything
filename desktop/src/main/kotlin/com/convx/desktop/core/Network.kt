/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.core

import com.convx.desktop.data.Settings
import com.music.innertube.YouTube
import com.music.innertube.models.IpVersion
import okhttp3.Credentials
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * The phone's Network & proxy settings: which IP version YouTube is reached over, and an HTTP or
 * SOCKS proxy for everything Convx fetches.
 *
 * Convx installs one proxy selector and one authenticator for the whole app before any client
 * exists, and only changes what they answer, so a new proxy applies at once without a restart.
 * Your own PC (localhost) is never sent through the proxy.
 */
object Network {
    const val KeyProxyPassword = "proxy.password"

    private val system: ProxySelector? = ProxySelector.getDefault()

    @Volatile private var proxy: Proxy? = null
    @Volatile private var credentials: PasswordAuthentication? = null

    private val selector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val host = uri?.host.orEmpty()
            if (host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1") return listOf(Proxy.NO_PROXY)
            return proxy?.let { listOf(it) } ?: system?.select(uri) ?: listOf(Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            if (proxy == null) system?.connectFailed(uri, sa, ioe)
        }
    }

    private val authenticator = object : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? =
            credentials?.takeIf { requestorType == RequestorType.PROXY }
    }

    /** Called first thing at launch, and again whenever the Network page changes something. */
    fun apply(settings: Settings) {
        ProxySelector.setDefault(selector)
        Authenticator.setDefault(authenticator)
        val next = if (settings.proxyEnabled) parse(settings.proxyType, settings.proxyUrl) else null
        val user = settings.proxyUsername
        val password = Secrets[KeyProxyPassword].orEmpty()
        proxy = next
        credentials = if (next != null && (user.isNotEmpty() || password.isNotEmpty())) PasswordAuthentication(user, password.toCharArray()) else null
        // The YouTube client sends HTTP proxy credentials itself, as the phone's does.
        YouTube.proxyAuth = if (next?.type() == Proxy.Type.HTTP && credentials != null) Credentials.basic(user, password) else null
        // Setting the IP version rebuilds the YouTube client, which picks up the credentials.
        YouTube.ipVersion = runCatching { IpVersion.valueOf(settings.ipVersion) }.getOrDefault(IpVersion.IPV4)
    }

    /** "host:port" as the phone takes it; null when it doesn't parse. */
    fun parse(type: String, address: String): Proxy? {
        val text = address.trim().removePrefix("http://").removePrefix("https://").removePrefix("socks5://").removeSuffix("/")
        val host = text.substringBeforeLast(':', "").removePrefix("[").removeSuffix("]")
        val port = text.substringAfterLast(':', "").toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) return null
        val kind = if (type.equals("SOCKS", ignoreCase = true)) Proxy.Type.SOCKS else Proxy.Type.HTTP
        return Proxy(kind, InetSocketAddress.createUnresolved(host, port))
    }
}
