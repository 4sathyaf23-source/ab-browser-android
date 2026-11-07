package com.abbrowser.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private class Tab(val webView: WebView, var title: String = "New tab")

    private val tabs = mutableListOf<Tab>()
    private var current: Tab? = null

    private lateinit var urlBar: EditText
    private lateinit var tabsBtn: TextView
    private lateinit var menuBtn: TextView
    private lateinit var progress: ProgressBar
    private lateinit var container: FrameLayout
    private lateinit var fullscreen: FrameLayout

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private val homeUrl = "file:///android_asset/home.html"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlBar = findViewById(R.id.urlBar)
        tabsBtn = findViewById(R.id.tabsBtn)
        menuBtn = findViewById(R.id.menuBtn)
        progress = findViewById(R.id.progress)
        container = findViewById(R.id.container)
        fullscreen = findViewById(R.id.fullscreen)

        urlBar.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                navigate(urlBar.text.toString())
                hideKeyboard()
                true
            } else {
                false
            }
        }
        tabsBtn.setOnClickListener { showTabs() }
        menuBtn.setOnClickListener { showMenu() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val tab = current
                when {
                    customView != null -> hideCustomView()
                    tab != null && tab.webView.canGoBack() -> tab.webView.goBack()
                    tab != null && tabs.size > 1 -> closeTab(tab)
                    else -> finish()
                }
            }
        })

        val link = intent?.data
        if (link != null) {
            selectTab(createTab(link.toString()))
        } else {
            selectTab(createTab(homeUrl))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val link = intent.data
        if (link != null) selectTab(createTab(link.toString()))
    }

    override fun onPause() {
        super.onPause()
        current?.webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        current?.webView?.onResume()
    }

    override fun onDestroy() {
        for (tab in tabs) {
            container.removeView(tab.webView)
            tab.webView.destroy()
        }
        tabs.clear()
        super.onDestroy()
    }

    // ---------- Tabs ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTab(url: String?): Tab {
        val wv = WebView(this)
        val tab = Tab(wv)

        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        wv.visibility = View.GONE

        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // Look like regular mobile Chrome rather than an embedded WebView
        s.userAgentString = s.userAgentString.replace("; wv", "").replace("Version/4.0 ", "")

        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (view === current?.webView) {
                    progress.visibility = View.VISIBLE
                    urlBar.setText(displayUrl(url))
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (view === current?.webView) {
                    progress.visibility = View.GONE
                    urlBar.setText(displayUrl(url))
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return false
                if (scheme == "http" || scheme == "https" || scheme == "file" ||
                    scheme == "about" || scheme == "data" || scheme == "blob"
                ) {
                    return false
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (e: Exception) {
                    // no app can handle this link
                }
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view === current?.webView) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                tabs.firstOrNull { it.webView === view }?.title = title ?: ""
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val newTab = createTab(null)
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = newTab.webView
                resultMsg.sendToTarget()
                selectTab(newTab)
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view
                customCallback = callback
                fullscreen.addView(view)
                fullscreen.visibility = View.VISIBLE
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }

        wv.setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, _ ->
            startDownload(dlUrl, userAgent, contentDisposition, mimeType)
        }

        container.addView(wv)
        tabs.add(tab)
        if (url != null) wv.loadUrl(url)
        updateTabCount()
        return tab
    }

    private fun selectTab(tab: Tab) {
        current?.webView?.visibility = View.GONE
        current = tab
        tab.webView.visibility = View.VISIBLE
        urlBar.setText(displayUrl(tab.webView.url))
        progress.visibility = View.GONE
        updateTabCount()
    }

    private fun closeTab(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index == -1) return
        tabs.removeAt(index)
        container.removeView(tab.webView)
        tab.webView.destroy()
        if (tabs.isEmpty()) {
            selectTab(createTab(homeUrl))
        } else {
            current = null
            selectTab(tabs[maxOf(0, index - 1)])
        }
        updateTabCount()
    }

    private fun updateTabCount() {
        tabsBtn.text = tabs.size.toString()
    }

    private fun showTabs() {
        val titles = tabs.map { t ->
            if (t.title.isNotBlank()) t.title else (t.webView.url ?: "New tab")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Tabs (" + tabs.size + ")")
            .setItems(titles) { _, i -> selectTab(tabs[i]) }
            .setPositiveButton("New tab") { _, _ -> selectTab(createTab(homeUrl)) }
            .setNeutralButton("Close this tab") { _, _ -> current?.let { closeTab(it) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMenu() {
        val popup = PopupMenu(this, menuBtn)
        popup.menu.add(0, 1, 0, "New tab")
        popup.menu.add(0, 2, 1, "Forward")
        popup.menu.add(0, 3, 2, "Reload")
        popup.menu.add(0, 4, 3, "Home")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> selectTab(createTab(homeUrl))
                2 -> current?.webView?.let { if (it.canGoForward()) it.goForward() }
                3 -> current?.webView?.reload()
                4 -> current?.webView?.loadUrl(homeUrl)
            }
            true
        }
        popup.show()
    }

    // ---------- Navigation ----------

    private fun navigate(input: String) {
        val url = toUrl(input) ?: return
        current?.webView?.loadUrl(url)
    }

    private fun toUrl(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(t)) return t
        val domain = Regex("^([a-z0-9-]+\\.)+[a-z]{2,}(:\\d+)?(/.*)?$", RegexOption.IGNORE_CASE)
        val local = Regex("^localhost(:\\d+)?(/.*)?$", RegexOption.IGNORE_CASE)
        if (domain.matches(t) || local.matches(t)) return "https://$t"
        return "https://www.google.com/search?q=" + Uri.encode(t)
    }

    private fun displayUrl(url: String?): String {
        if (url == null || url.startsWith("file:///android_asset")) return ""
        return url
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }

    // ---------- Fullscreen video ----------

    private fun hideCustomView() {
        val view = customView ?: return
        fullscreen.removeView(view)
        fullscreen.visibility = View.GONE
        customCallback?.onCustomViewHidden()
        customView = null
        customCallback = null
    }

    // ---------- Downloads ----------

    private fun startDownload(url: String, userAgent: String?, disposition: String?, mimeType: String?) {
        try {
            val name = URLUtil.guessFileName(url, disposition, mimeType)
            val req = DownloadManager.Request(Uri.parse(url))
            if (mimeType != null) req.setMimeType(mimeType)
            val cookie = CookieManager.getInstance().getCookie(url)
            if (cookie != null) req.addRequestHeader("cookie", cookie)
            if (userAgent != null) req.addRequestHeader("User-Agent", userAgent)
            req.setTitle(name)
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (Build.VERSION.SDK_INT >= 29) {
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            } else {
                req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name)
            }
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            Toast.makeText(this, "Downloading $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show()
        }
    }
}
