package com.bioinventics.articleledger

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.MailTo
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * Article Ledger: the whole app UI lives in assets/index.html.
 * This activity hosts it in a WebView, keeps its data (localStorage) on the phone,
 * and gives it native file save/open and the Android back button.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var pendingText: String? = null
    private var pendingName: String? = null

    private val saveJson =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> writePending(uri) }
    private val saveCsv =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> writePending(uri) }
    private val openFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> readPicked(uri) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0F5F58"))
        webView = WebView(this)
        root.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(root)

        // Keep content clear of the status bar, navigation bar and keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == "appassets.androidplatform.net") return false
                openExternal(url)
                return true
            }
        }
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("(window.appBack && window.appBack()) ? 'yes' : 'no'") { result ->
                    if (result != "\"yes\"") finish()
                }
            }
        })
    }

    /** Methods the web page can call as window.AndroidBridge.xxx() */
    inner class Bridge {
        @JavascriptInterface
        fun saveFile(name: String, mime: String, content: String) {
            runOnUiThread {
                pendingText = content
                pendingName = name
                if (mime.contains("csv")) saveCsv.launch(name) else saveJson.launch(name)
            }
        }

        @JavascriptInterface
        fun pickFile() {
            runOnUiThread { openFile.launch("*/*") }
        }
    }

    private fun writePending(uri: Uri?) {
        val text = pendingText
        val name = pendingName ?: ""
        pendingText = null
        pendingName = null
        if (uri == null || text == null) {
            callJs("window.onNativeSaved(false)")
            return
        }
        val ok = try {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
        } catch (e: Exception) {
            false
        }
        callJs("window.onNativeSaved($ok, ${JSONObject.quote(name)})")
    }

    private fun readPicked(uri: Uri?) {
        if (uri == null) return
        val text = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        }
        if (text == null) {
            Toast.makeText(this, "Could not read that file", Toast.LENGTH_SHORT).show()
            return
        }
        callJs("window.onNativeFileLoaded(${JSONObject.quote(text)})")
    }

    private fun openExternal(uri: Uri) {
        val intent = if (uri.scheme == "mailto") {
            val mail = MailTo.parse(uri.toString())
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(Uri.decode(mail.to ?: "")))
                putExtra(Intent.EXTRA_SUBJECT, mail.subject ?: "")
                putExtra(Intent.EXTRA_TEXT, mail.body ?: "")
            }
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to open this", Toast.LENGTH_SHORT).show()
        }
    }

    private fun callJs(js: String) = webView.evaluateJavascript(js, null)
}
