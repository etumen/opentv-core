/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider.dizipal

import android.content.Context
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener

/**
 * DiziPal rejects plain HTTP clients with a Cloudflare managed challenge while Android WebView
 * reaches the real site. Keep one hidden Chromium session and run same-origin fetch() calls inside
 * it. Only DiziPal pages use this transport; resolved video/CDN URLs still go through OkHttp/Media3.
 */
internal class DiziPalBrowserSession(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val requestIds = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<BrowserResult>>()

    @Volatile private var webView: WebView? = null
    @Volatile private var origin: String? = null
    @Volatile private var ready = false

    private data class BrowserResult(
        val status: Int,
        val body: String,
    )

    private inner class Bridge {
        @JavascriptInterface
        fun complete(id: Int, status: Int, payload: String) {
            val waiter = pending.remove(id) ?: return
            val body = runCatching {
                String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrElse { "" }
            waiter.complete(BrowserResult(status, body))
        }

        @JavascriptInterface
        fun fail(id: Int, message: String) {
            pending.remove(id)?.completeExceptionally(IOException(message))
        }
    }

    @Volatile private var verificationCallback: (() -> Unit)? = null

    fun verificationView(
        targetOrigin: String,
        onReady: () -> Unit,
    ): WebView {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "DiziPal verification WebView must be created on the main thread"
        }

        webView?.takeIf { origin == targetOrigin }?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            verificationCallback = onReady
            if (ready) existing.post { onReady() }
            return existing
        }

        webView?.let { old ->
            runCatching {
                old.stopLoading()
                (old.parent as? ViewGroup)?.removeView(old)
                old.destroy()
            }
        }

        ready = false
        origin = targetOrigin
        verificationCallback = onReady

        val view = WebView(appContext)
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(view, true)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.userAgentString = DiziPalProvider.SITE_USER_AGENT
        view.settings.loadsImagesAutomatically = true
        view.settings.javaScriptCanOpenWindowsAutomatically = true
        view.addJavascriptInterface(Bridge(), BRIDGE_NAME)

        fun pollUntilRealSite() {
            if (ready) return
            val probe = """
                (function() {
                  var html = (document.documentElement &&
                    document.documentElement.innerHTML || '').toLowerCase();
                  var title = (document.title || '').toLowerCase();
                  var cloudflare =
                    html.indexOf('cf-chl') >= 0 ||
                    html.indexOf('challenge-platform') >= 0 ||
                    title.indexOf('just a moment') >= 0 ||
                    title.indexOf('bir dakika') >= 0;
                  var realSite =
                    document.querySelector('#router-view') ||
                    document.querySelector('ul.content-grid') ||
                    document.querySelector('.episodes-list-grid') ||
                    document.querySelector('a[href*="/series/"]') ||
                    document.querySelector('a[href*="/dizi/"]') ||
                    document.querySelector('a[href="/diziler"]') ||
                    document.querySelector('#videoContainer') ||
                    document.querySelector('script[src*="app-dizipals"]') ||
                    (document.body &&
                      document.body.innerText.indexOf('Trend Diziler') >= 0);
                  return (!cloudflare && !!realSite) ? 'READY' : 'WAIT';
                })();
            """.trimIndent()

            view.evaluateJavascript(probe) { result ->
                if (result == "\"READY\"" && !ready) {
                    ready = true
                    cookies.flush()
                    Log.d(TAG, "Visible verification reached real DiziPal DOM")
                    verificationCallback?.let { callback ->
                        verificationCallback = null
                        callback()
                    }
                } else if (!ready) {
                    view.postDelayed({ pollUntilRealSite() }, READY_POLL_MILLIS)
                }
            }
        }

        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url?.startsWith(targetOrigin) == true && !ready) {
                    Log.d(TAG, "Visible verification page finished: " + url)
                    pollUntilRealSite()
                }
            }
        }

        webView = view
        view.loadUrl(
            targetOrigin + "/",
            mapOf("Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6"),
        )
        view.postDelayed({ pollUntilRealSite() }, READY_POLL_MILLIS)
        return view
    }

    fun detachVerificationCallback() {
        verificationCallback = null
    }

    suspend fun get(
        url: String,
        ajax: Boolean = false,
        noCache: Boolean = false,
    ): String = mutex.withLock {
        val targetOrigin = originOf(url)
        ensureReady(targetOrigin)

        val id = requestIds.getAndIncrement()
        val waiter = CompletableDeferred<BrowserResult>()
        pending[id] = waiter

        val headers = buildList {
            fun header(name: String, value: String): String =
                JSONObject.quote(name) + ":" + JSONObject.quote(value)

            if (ajax) {
                add(header("Accept", "application/json, text/javascript, */*; q=0.01"))
                add(header("X-Requested-With", "XMLHttpRequest"))
            } else {
                add(
                    header(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    ),
                )
            }
            add(header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6"))
            if (noCache) {
                add(header("Cache-Control", "no-cache"))
                add(header("Pragma", "no-cache"))
            }
        }.joinToString(",")

        val jsUrl = jsString(url)
        val cache = if (noCache) "no-store" else "default"
        val script = """
            (function() {
              fetch($jsUrl, {
                method: 'GET',
                credentials: 'include',
                cache: '$cache',
                headers: {$headers}
              }).then(async function(r) {
                var text = await r.text();
                var bytes = unescape(encodeURIComponent(text));
                var b64 = btoa(bytes);
                OpenTvDiziPalBridge.complete($id, r.status, b64);
              }).catch(function(e) {
                OpenTvDiziPalBridge.fail($id, String(e));
              });
            })();
        """.trimIndent()

        withContext(Dispatchers.Main.immediate) {
            webView?.evaluateJavascript(script, null)
                ?: throw IOException("DiziPal browser session is unavailable")
        }

        val result = try {
            withTimeout(REQUEST_TIMEOUT_MILLIS) { waiter.await() }
        } finally {
            pending.remove(id)
        }

        Log.d(TAG, "Browser fetch HTTP " + result.status + " " + url)
        if (result.status !in 200..299) {
            throw IOException("HTTP " + result.status + " for " + url)
        }
        result.body
    }

    suspend fun renderedPlayerUrl(
        url: String,
    ): String? = mutex.withLock {
        val targetOrigin = originOf(url)
        ensureReady(targetOrigin)

        val result = CompletableDeferred<String?>()
        withContext(Dispatchers.Main.immediate) {
            val view = webView
                ?: throw IOException("DiziPal browser session is unavailable")

            fun poll() {
                if (result.isCompleted) return
                val script = """
                    (function() {
                      var iframe = document.querySelector('#cstk iframe, iframe[src]');
                      var iframeUrl = iframe &&
                        (iframe.src || iframe.getAttribute('data-src') || iframe.getAttribute('src'));
                      if (iframeUrl) return iframeUrl;

                      var video = document.querySelector('video');
                      var source = document.querySelector('video source, source[src]');
                      var videoUrl =
                        (video && (video.currentSrc || video.src)) ||
                        (source && source.src);
                      if (videoUrl) return videoUrl;

                      var html = document.documentElement &&
                        document.documentElement.innerHTML || '';
                      var match = html.match(/https?:\\/\\/[^"'\\s<>]+\\.m3u8[^"'\\s<>]*/i);
                      return match ? match[0] : '';
                    })();
                """.trimIndent()

                view.evaluateJavascript(script) { raw ->
                    val value = runCatching {
                        JSONTokener(raw).nextValue() as? String
                    }.getOrNull().orEmpty()
                        .replace("\\/", "/")
                        .trim()

                    if (value.startsWith("http://") ||
                        value.startsWith("https://") ||
                        value.startsWith("//")
                    ) {
                        if (!result.isCompleted) result.complete(value)
                    } else if (!result.isCompleted) {
                        view.postDelayed({ poll() }, READY_POLL_MILLIS)
                    }
                }
            }

            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    if (pageUrl?.startsWith(targetOrigin) == true && !result.isCompleted) {
                        poll()
                    }
                }
            }
            view.loadUrl(
                url,
                mapOf("Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6"),
            )
            view.postDelayed({ poll() }, READY_POLL_MILLIS)
        }

        runCatching {
            withTimeout(PLAYER_TIMEOUT_MILLIS) { result.await() }
        }.getOrNull().also { playerUrl ->
            Log.d(TAG, "Rendered player URL: " + (playerUrl ?: "<none>"))
        }
    }

    suspend fun postForm(
        url: String,
        data: List<Pair<String, String>>,
        ajax: Boolean = true,
        noCache: Boolean = false,
    ): String = mutex.withLock {
        val targetOrigin = originOf(url)
        ensureReady(targetOrigin)

        val id = requestIds.getAndIncrement()
        val waiter = CompletableDeferred<BrowserResult>()
        pending[id] = waiter

        val headers = buildList {
            fun header(name: String, value: String): String =
                JSONObject.quote(name) + ":" + JSONObject.quote(value)

            add(header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8"))
            if (ajax) {
                add(header("Accept", "application/json, text/javascript, */*; q=0.01"))
                add(header("X-Requested-With", "XMLHttpRequest"))
            }
            add(header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6"))
            if (noCache) {
                add(header("Cache-Control", "no-cache"))
                add(header("Pragma", "no-cache"))
            }
        }.joinToString(",")

        val formScript = data.joinToString("\n") { (name, value) ->
            "body.append(" + JSONObject.quote(name) + "," + JSONObject.quote(value) + ");"
        }
        val jsUrl = jsString(url)
        val cache = if (noCache) "no-store" else "default"
        val script = """
            (function() {
              var body = new URLSearchParams();
              $formScript
              fetch($jsUrl, {
                method: 'POST',
                credentials: 'include',
                cache: '$cache',
                headers: {$headers},
                body: body.toString()
              }).then(async function(r) {
                var text = await r.text();
                var bytes = unescape(encodeURIComponent(text));
                var b64 = btoa(bytes);
                OpenTvDiziPalBridge.complete($id, r.status, b64);
              }).catch(function(e) {
                OpenTvDiziPalBridge.fail($id, String(e));
              });
            })();
        """.trimIndent()

        withContext(Dispatchers.Main.immediate) {
            webView?.evaluateJavascript(script, null)
                ?: throw IOException("DiziPal browser session is unavailable")
        }

        val result = try {
            withTimeout(REQUEST_TIMEOUT_MILLIS) { waiter.await() }
        } finally {
            pending.remove(id)
        }

        Log.d(TAG, "Browser POST HTTP " + result.status + " " + url)
        if (result.status !in 200..299) {
            throw IOException("HTTP " + result.status + " for " + url)
        }
        result.body
    }

    private suspend fun ensureReady(targetOrigin: String) {
        if (ready && origin == targetOrigin && webView != null) return

        withContext(Dispatchers.Main.immediate) {
            webView?.let { old ->
                runCatching {
                    old.stopLoading()
                    old.destroy()
                }
            }

            ready = false
            origin = targetOrigin
            val loaded = CompletableDeferred<Unit>()
            val view = WebView(appContext)
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            cookies.setAcceptThirdPartyCookies(view, true)
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.userAgentString = DiziPalProvider.SITE_USER_AGENT
            view.settings.loadsImagesAutomatically = true
            view.settings.javaScriptCanOpenWindowsAutomatically = true
            view.addJavascriptInterface(Bridge(), BRIDGE_NAME)

            fun pollUntilRealSite() {
                if (loaded.isCompleted) return
                val probe = """
                    (function() {
                      var html = (document.documentElement && document.documentElement.innerHTML || '').toLowerCase();
                      var title = (document.title || '').toLowerCase();
                      var cloudflare =
                        html.indexOf('cf-chl') >= 0 ||
                        html.indexOf('challenge-platform') >= 0 ||
                        title.indexOf('just a moment') >= 0 ||
                        title.indexOf('bir dakika') >= 0;
                      var realSite =
                        document.querySelector('#router-view') ||
                        document.querySelector('ul.content-grid') ||
                        document.querySelector('.episodes-list-grid') ||
                        document.querySelector('a[href*="/series/"]') ||
                        document.querySelector('a[href*="/dizi/"]') ||
                        document.querySelector('a[href="/diziler"]') ||
                        document.querySelector('#videoContainer') ||
                        document.querySelector('script[src*="app-dizipals"]') ||
                        (document.body &&
                          document.body.innerText.indexOf('Trend Diziler') >= 0);
                      return (!cloudflare && !!realSite) ? 'READY' : 'WAIT';
                    })();
                """.trimIndent()

                view.evaluateJavascript(probe) { result ->
                    if (result == "\"READY\"" && !loaded.isCompleted) {
                        Log.d(TAG, "Browser bootstrap reached real DiziPal DOM")
                        loaded.complete(Unit)
                    } else if (!loaded.isCompleted) {
                        view.postDelayed({ pollUntilRealSite() }, READY_POLL_MILLIS)
                    }
                }
            }

            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url?.startsWith(targetOrigin) == true && !loaded.isCompleted) {
                        Log.d(TAG, "Browser page finished, probing DOM: " + url)
                        pollUntilRealSite()
                    }
                }
            }
            webView = view
            view.loadUrl(
                targetOrigin + "/",
                mapOf("Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6"),
            )
            view.postDelayed({ pollUntilRealSite() }, READY_POLL_MILLIS)

            try {
                withTimeout(BOOTSTRAP_TIMEOUT_MILLIS) { loaded.await() }
                ready = true
            } catch (error: Exception) {
                ready = false
                throw IOException("DiziPal browser bootstrap did not reach the real site", error)
            }
        }
    }

    private fun originOf(url: String): String {
        val uri = URI(url)
        val port = if (uri.port == -1) "" else ":" + uri.port
        return uri.scheme + "://" + uri.host + port
    }

    private fun jsString(value: String): String = JSONObject.quote(value)

    companion object {
        private const val TAG = "DiziPalBrowser"
        private const val BRIDGE_NAME = "OpenTvDiziPalBridge"
        private const val BOOTSTRAP_TIMEOUT_MILLIS = 25_000L
        private const val REQUEST_TIMEOUT_MILLIS = 20_000L
        private const val PLAYER_TIMEOUT_MILLIS = 15_000L
        private const val READY_POLL_MILLIS = 500L
    }
}
