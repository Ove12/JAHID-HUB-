package com.lagradost.cloudstream3.network

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.AnyThread
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.mvvm.debugWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight, resource-efficient Cloudflare bypass interceptor.
 * Designed to minimize CPU, memory, battery, and network consumption:
 * 1. Single concurrent solver across the app via Mutex (prevents multiple WebViews).
 * 2. Caches cf_clearance cookies & solving User-Agents per host.
 * 3. 2-minute cooldown for unsolvable/interactive challenge hosts (prevents battery drain & loops).
 * 4. Ultra-lean headless WebView: network images, media, CSS, and tracker requests are blocked.
 * 5. Short 12-second timeout and instant WebView destruction upon cookie discovery.
 */
@AnyThread
object CloudflareKiller : Interceptor {
    private const val TAG = "CloudflareKiller"

    // Only allow ONE WebView challenge resolution in the entire app at any given time
    private val cloudflareMutex = Mutex()

    // Thread-safe caches
    val savedCookies = ConcurrentHashMap<String, Map<String, String>>()
    val savedUserAgents = ConcurrentHashMap<String, String>()
    val failedHostCooldown = ConcurrentHashMap<String, Long>()

    fun parseCookieMap(cookie: String): Map<String, String> {
        return cookie.split(";").associate {
            val split = it.split("=")
            (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
        }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
    }

    private fun isCloudflareBlocked(response: Response): Boolean {
        if (response.code !in listOf(403, 503)) return false
        val server = response.header("Server")?.lowercase() ?: ""
        val cfRay = response.header("cf-ray")
        val cfMitigated = response.header("cf-mitigated")
        val cfCache = response.header("cf-cache-status")
        return server.contains("cloudflare") || cfRay != null || cfMitigated != null || cfCache != null
    }

    private fun isEligibleForBypass(request: Request): Boolean {
        if (request.method !in listOf("GET", "POST", "HEAD")) return false
        val path = request.url.encodedPath.lowercase()
        val blacklistedExts = listOf(
            ".m3u8", ".ts", ".mp4", ".m4s", ".webm", ".mkv", ".mp3",
            ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".ico",
            ".woff", ".woff2", ".ttf", ".eot",
            ".srt", ".vtt", ".ass"
        )
        return !blacklistedExts.any { path.endsWith(it) }
    }

    private fun attachCookiesAndUserAgent(
        request: Request,
        cookies: Map<String, String>,
        userAgent: String?
    ): Request {
        val builder = request.newBuilder()
        val existingCookieHeader = request.header("Cookie") ?: ""
        val existingCookieMap = if (existingCookieHeader.isNotBlank()) parseCookieMap(existingCookieHeader) else emptyMap()
        val combinedCookies = existingCookieMap + cookies
        val cookieString = combinedCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        builder.header("Cookie", cookieString)

        if (!userAgent.isNullOrBlank()) {
            builder.header("User-Agent", userAgent)
        }

        return builder.build()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        // 1. If we have saved cookies for this host, attach them and proceed
        val existingCookies = savedCookies[host]
        val initialRequest = if (!existingCookies.isNullOrEmpty()) {
            attachCookiesAndUserAgent(request, existingCookies, savedUserAgents[host])
        } else {
            request
        }

        val response = chain.proceed(initialRequest)

        // 2. Check if blocked by Cloudflare challenge
        if (isCloudflareBlocked(response) && isEligibleForBypass(request)) {
            response.close() // Close response body to prevent socket leak

            // If we already sent cookies and it still failed, the cookies are expired
            if (!existingCookies.isNullOrEmpty()) {
                savedCookies.remove(host)
                savedUserAgents.remove(host)
            }

            // Check cooldown to avoid hammering the phone if a host has an unbreakable captcha
            val now = System.currentTimeMillis()
            val cooldownUntil = failedHostCooldown[host] ?: 0L
            if (now < cooldownUntil) {
                Log.d(TAG, "Host $host is on Cloudflare cooldown, skipping WebView bypass to save phone resources")
                return chain.proceed(request)
            }

            val newCookies = runBlocking {
                bypassCloudflare(request)
            }

            if (newCookies != null) {
                val retriedRequest = attachCookiesAndUserAgent(request, newCookies, savedUserAgents[host])
                return chain.proceed(retriedRequest)
            } else {
                // Set 2-minute cooldown on this host so we don't spam WebViews
                failedHostCooldown[host] = now + 120_000L
                debugWarning({ true }) { "Failed Cloudflare bypass for $host" }
            }
        }

        return response
    }

    private fun isBlockedResource(url: String): Boolean {
        val blockedExts = listOf(
            ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".ico",
            ".mp4", ".mp3", ".webm", ".mkv", ".m3u8", ".ts",
            ".woff", ".woff2", ".ttf", ".otf", ".eot",
            ".css", ".vtt", ".srt"
        )
        return blockedExts.any { url.contains(it) } ||
                url.contains("google-analytics") ||
                url.contains("googletagmanager") ||
                url.contains("doubleclick") ||
                url.contains("facebook.net") ||
                url.contains("adservice") ||
                url.contains("clarity.ms") ||
                url.contains("disqus.com")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun bypassCloudflare(request: Request): Map<String, String>? = cloudflareMutex.withLock {
        val host = request.url.host
        // Check if another request solved it while we were waiting for the mutex
        savedCookies[host]?.let { return it }

        val context = CloudStreamApp.context ?: return null
        val url = request.url.toString()
        Log.i(TAG, "Starting lightweight Cloudflare bypass for $host ($url)")

        var webView: WebView? = null
        var solvedCookies: Map<String, String>? = null

        try {
            withTimeoutOrNull(12_000L) { // Maximum 12 seconds
                withContext(Dispatchers.Main) {
                    webView = WebView(context).apply {
                        layout(0, 0, 1024, 768)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = false
                            loadsImagesAutomatically = false
                            blockNetworkImage = true
                            setGeolocationEnabled(false)
                            mediaPlaybackRequiresUserGesture = true
                            cacheMode = WebSettings.LOAD_NO_CACHE
                        }

                        try {
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        } catch (_: Throwable) {}

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                webReq: WebResourceRequest
                            ): WebResourceResponse? {
                                val reqUrl = webReq.url.toString().lowercase()
                                if (reqUrl.contains("/cdn-cgi/") ||
                                    reqUrl.contains("cloudflare.com") ||
                                    reqUrl.contains("challenges.cloudflare.com") ||
                                    reqUrl.contains("turnstile")
                                ) {
                                    return null
                                }
                                if (isBlockedResource(reqUrl)) {
                                    return WebResourceResponse(
                                        "text/plain",
                                        "UTF-8",
                                        ByteArrayInputStream(ByteArray(0))
                                    )
                                }
                                return null
                            }
                        }
                    }
                    webView?.loadUrl(url)
                }

                while (solvedCookies == null) {
                    delay(250L)
                    val rawCookies = try {
                        CookieManager.getInstance().getCookie(url)
                    } catch (_: Throwable) {
                        null
                    }

                    if (rawCookies != null && rawCookies.contains("cf_clearance")) {
                        val parsed = parseCookieMap(rawCookies)
                        solvedCookies = parsed
                        savedCookies[host] = parsed
                        withContext(Dispatchers.Main) {
                            try {
                                val ua = webView?.settings?.userAgentString
                                if (!ua.isNullOrBlank()) {
                                    savedUserAgents[host] = ua
                                }
                            } catch (_: Throwable) {}
                        }
                        Log.i(TAG, "Successfully solved Cloudflare for $host in lightweight WebView!")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cloudflare bypass failed or timed out for $host: ${e.message}")
        } finally {
            withContext(Dispatchers.Main) {
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                } catch (_: Throwable) {}
                webView = null
            }
        }

        return solvedCookies
    }
}
