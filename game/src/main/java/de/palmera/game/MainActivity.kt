package de.palmera.game

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader

/**
 * Hostet das Spiel (three.js/WebGL2) in einer Vollbild-WebView.
 * Die Spieldateien liegen in assets/web und werden über den WebViewAssetLoader ausgeliefert.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Kamera-Ausschnitt (Punch-Hole beim S25) mitnutzen
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
            isFocusable = true
            isFocusableInTouchMode = true
            addJavascriptInterface(NativeBridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
            }
        }
        setContentView(webView)
        webView.requestFocus()
        hideSystemBars()
        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.evaluateJavascript("window.onNativeResume && window.onNativeResume()", null)
    }

    override fun onPause() {
        webView.evaluateJavascript("window.onNativePause && window.onNativePause()", null)
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    // Controller-Tasten (z. B. PS4-Kreis, das Android sonst als „Zurück“ deutet) direkt an die
    // WebView geben, damit sie über die Gamepad-API im Spiel ankommen und die App nicht schließen
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fromPad = event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        if (fromPad && ::webView.isInitialized) {
            webView.dispatchKeyEvent(event)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Zurück-Taste öffnet das Pausenmenü statt die App zu schließen
        webView.evaluateJavascript("window.onNativeBack ? window.onNativeBack() : false") { handled ->
            if (handled != "true") {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun vibrate(ms: Int) {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms.toLong().coerceIn(1, 500), VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }

        @JavascriptInterface
        fun quit() {
            runOnUiThread { finish() }
        }
    }
}
