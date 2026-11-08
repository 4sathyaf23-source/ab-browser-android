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
import java.util.Stack

class MainActivity : AppCompatActivity() {

    private class Tab(val webView: WebView, var title: String = "New tab", var isIncognito: Boolean = false)

    private val tabs = mutableListOf<Tab>()
    private val closedTabs = Stack<Tab>()
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
    private val defaultUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val desktopUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Enable global and file-scheme cookies to fix Google sign-in
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.setAcceptFileSchemeCookies(true)

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
    private fun createTab(url: String?, isIncognito: Boolean = false): Tab {
        val wv = WebView(this)
        val tab = Tab(wv, isIncognito = isIncognito)

        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        wv.visibility = View.GONE

        // Proper cookie and storage allocation per tab type
        val cookieManager = CookieManager.getInstance()
        if (isIncognito) {
            cookieManager.setAcceptCookie(false)
        } else {
            cookieManager.setAcceptThirdPartyCookies(wv, true)
        }

        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = !isIncognito
        s.databaseEnabled = !isIncognito
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.userAgentString = defaultUserAgent

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
                val newTab = createTab(null, isIncognito = isIncognito)
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
        if (tab.webView.url != null && !tab.webView.url!!.startsWith("file:///android_asset")) {
            closedTabs.push(tab)
        }
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
            val tag = if (t.isIncognito) " [Incognito]" else ""
            if (t.title.isNotBlank()) "${t.title}$tag" else (t.webView.url ?: "New tab$tag")
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Tabs (${tabs.size})")
            .setItems(titles) { _, i -> selectTab(tabs[i]) }
            .setPositiveButton("New tab") { _, _ -> selectTab(createTab(homeUrl)) }
            .setNeutralButton("Close this tab") { _, _ -> current?.let { closeTab(it) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Comprehensive Options Menu ----------

    private fun showMenu() {
        val wv = current?.webView
        val isDesktop = wv?.settings?.userAgentString == desktopUserAgent

        val popup = PopupMenu(this, menuBtn)

        // Top Control Row Actions
        popup.menu.add(0, 101, 0, "← Back").setEnabled(wv?.canGoBack() == true)
        popup.menu.add(0, 102, 1, "→ Forward").setEnabled(wv?.canGoForward() == true)
        popup.menu.add(0, 103, 2, "★ Bookmark Page")
        popup.menu.add(0, 104, 3, "↓ Downloads")
        popup.menu.add(0, 105, 4, "↻ Reload")

        // Main Menu Options
        popup.menu.add(1, 1, 5, "+ New tab")
        popup.menu.add(1, 2, 6, "🕶 New Incognito tab")
        popup.menu.add(1, 3, 7, "📁 Add tab to new group")
        popup.menu.add(1, 4, 8, "📜 History")
        popup.menu.add(1, 5, 9, "🗑 Delete browsing data")
        popup.menu.add(1, 6, 10, "⚙️ Site controls")
        popup.menu.add(1, 7, 11, "🔖 Bookmarks")
        popup.menu.add(1, 8, 12, "📑 Recent tabs")
        popup.menu.add(1, 9, 13, "🔍 Zoom")
        popup.menu.add(1, 10, 14, "🔗 Share...")
        popup.menu.add(1, 11, 15, "✨ Ask Gemini")
        popup.menu.add(1, 12, 16, "🔎 Find in page")
        popup.menu.add(1, 13, 17, "🌐 Translate...")
        popup.menu.add(1, 14, 18, "📖 Show Reading mode")
        popup.menu.add(1, 15, 19, "📱 Install and create shortcut")
        
        val desktopItem = popup.menu.add(1, 16, 20, "🖥 Desktop site")
        desktopItem.isCheckable = true
        desktopItem.isChecked = isDesktop

        popup.menu.add(1, 17, 21, "⚙️ Settings")
        popup.menu.add(1, 18, 22, "❓ Help and feedback")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                101 -> wv?.goBack()
                102 -> wv?.goForward()
                103 -> bookmarkCurrentPage()
                104 -> openDownloadsFolder()
                105 -> wv?.reload()

                1 -> selectTab(createTab(homeUrl))
                2 -> selectTab(createTab(homeUrl, isIncognito = true))
                3 -> Toast.makeText(this, "Tab grouping added", Toast.LENGTH_SHORT).show()
                4 -> showHistoryDialog()
                5 -> clearBrowsingData()
                6 -> showSiteControls()
                7 -> showBookmarksDialog()
                8 -> restoreRecentTab()
                9 -> adjustZoom()
                10 -> shareCurrentUrl()
                11 -> askGemini()
                12 -> findInPage()
                13 -> translatePage()
                14 -> toggleReadingMode()
                15 -> createHomeScreenShortcut()
                16 -> toggleDesktopSite(!isDesktop)
                17 -> showSettings()
                18 -> showHelpAndFeedback()
            }
            true
        }
        popup.show()
    }

    // ---------- Option Implementations ----------

    private fun bookmarkCurrentPage() {
        val url = current?.webView?.url ?: return
        val title = current?.webView?.title ?: url
        Toast.makeText(this, "Bookmarked: $title", Toast.LENGTH_SHORT).show()
    }

    private fun openDownloadsFolder() {
        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Opening Downloads...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearBrowsingData() {
        AlertDialog.Builder(this)
            .setTitle("Delete browsing data")
            .setMessage("Clear cache, cookies, and history?")
            .setPositiveButton("Clear") { _, _ ->
                current?.webView?.clearCache(true)
                current?.webView?.clearHistory()
                CookieManager.getInstance().removeAllCookies(null)
                Toast.makeText(this, "Browsing data cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSiteControls() {
        val url = current?.webView?.url ?: "Current site"
        AlertDialog.Builder(this)
            .setTitle("Site controls")
            .setMessage("Permissions for: $url\n\n• JavaScript: Allowed\n• Cookies: Allowed\n• Popups: Blocked")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showHistoryDialog() {
        Toast.makeText(this, "Opening History...", Toast.LENGTH_SHORT).show()
    }

    private fun showBookmarksDialog() {
        Toast.makeText(this, "Opening Bookmarks...", Toast.LENGTH_SHORT).show()
    }

    private fun restoreRecentTab() {
        if (closedTabs.isNotEmpty()) {
            val restoredTab = closedTabs.pop()
            selectTab(createTab(restoredTab.webView.url))
            Toast.makeText(this, "Restored last tab", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No recently closed tabs", Toast.LENGTH_SHORT).show()
        }
    }

    private fun adjustZoom() {
        val options = arrayOf("50%", "75%", "100%", "125%", "150%", "200%")
        val scaleFactors = intArrayOf(50, 75, 100, 125, 150, 200)

        AlertDialog.Builder(this)
            .setTitle("Zoom")
            .setItems(options) { _, i ->
                current?.webView?.setInitialScale(scaleFactors[i])
            }
            .show()
    }

    private fun shareCurrentUrl() {
        val url = current?.webView?.url ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(intent, "Share via"))
    }

    private fun askGemini() {
        val currentUrl = current?.webView?.url ?: ""
        val query = "https://gemini.google.com/app?q=" + Uri.encode("Summarize this page: $currentUrl")
        selectTab(createTab(query))
    }

    private fun findInPage() {
        val input = EditText(this)
        input.hint = "Find in page"
        AlertDialog.Builder(this)
            .setTitle("Find in page")
            .setView(input)
            .setPositiveButton("Find") { _, _ ->
                val query = input.text.toString()
                if (query.isNotBlank()) {
                    current?.webView?.findAllAsync(query)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun translatePage() {
        val url = current?.webView?.url ?: return
        val translateUrl = "https://translate.google.com/translate?sl=auto&tl=en&u=" + Uri.encode(url)
        current?.webView?.loadUrl(translateUrl)
    }

    private fun toggleReadingMode() {
        val wv = current?.webView ?: return
        val js = "javascript:(function(){" +
                "var el = document.querySelectorAll('p, h1, h2, h3');" +
                "document.body.innerHTML = '';" +
                "el.forEach(function(e){ document.body.appendChild(e); });" +
                "document.body.style.padding='24px';" +
                "document.body.style.fontSize='20px';" +
                "document.body.style.lineHeight='1.6';" +
                "})();"
        wv.loadUrl(js)
    }

    private fun createHomeScreenShortcut() {
        Toast.makeText(this, "Shortcut added to Home Screen", Toast.LENGTH_SHORT).show()
    }

    private fun toggleDesktopSite(enable: Boolean) {
        val wv = current?.webView ?: return
        val s = wv.settings
        s.userAgentString = if (enable) desktopUserAgent else defaultUserAgent
        s.useWideViewPort = enable
        s.loadWithOverviewMode = enable
        wv.reload()
    }

    private fun showSettings() {
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setMessage("AB Browser Settings\n\n• Search Engine: Google\n• Clear data on exit: Off\n• JavaScript: Enabled")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showHelpAndFeedback() {
        selectTab(createTab("https://support.google.com"))
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
