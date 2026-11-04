package com.browdika.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import com.browdika.browser.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    private val tabs = mutableListOf<BrowserTab>()
    private var currentTabIndex = 0
    private val currentWebView: WebView
        get() = tabs[currentTabIndex].webView

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var fallbackBlockedUntil = 0L
    private lateinit var mobileUserAgent: String
    private var tabsDialog: BottomSheetDialog? = null
    private var downloadsDialog: BottomSheetDialog? = null
    private var downloadsAdapter: DownloadListAdapter? = null
    private val downloadsHandler = Handler(Looper.getMainLooper())
    private val downloadsRefreshRunnable = object : Runnable {
        override fun run() {
            refreshDownloadList()
            downloadsHandler.postDelayed(this, 1000L)
        }
    }

    private data class BrowserTab(
        val webView: WebView,
        var desktopFacebook: Boolean = false
    )

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback
            fileChooserCallback = null
            if (callback == null) return@registerForActivityResult

            if (result.resultCode != RESULT_OK) {
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }

            val data = result.data
            val uris: Array<Uri>? = when {
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
            callback.onReceiveValue(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        mobileUserAgent = WebSettings.getDefaultUserAgent(this)
            .replace("; wv", "") + " Browdika/1.0"
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().setAcceptCookie(true)

        setupToolbar()
        setupBackNavigation()
        requestLegacyStoragePermission()
        restoreOrCreateTabs(savedInstanceState)
        updateThemeButton()
    }

    private fun restoreOrCreateTabs(state: Bundle?) {
        val savedUrls = state?.getStringArray(KEY_TAB_URLS)
        if (!savedUrls.isNullOrEmpty()) {
            val savedDesktop = state.getBooleanArray(KEY_TAB_DESKTOP)
            savedUrls.forEachIndexed { index, url ->
                addTab(
                    url = url.ifBlank { HOME_URL },
                    select = false,
                    desktopFacebook = savedDesktop?.getOrNull(index) == true
                )
            }
            val index = state.getInt(KEY_CURRENT_TAB, 0)
                .coerceIn(0, tabs.lastIndex)
            selectTab(index)
            return
        }

        val data = intent?.data
        val initialUrl = if (data != null && (data.scheme == "http" || data.scheme == "https")) {
            data.toString()
        } else {
            HOME_URL
        }
        addTab(initialUrl)
    }

    private fun addTab(
        url: String = HOME_URL,
        select: Boolean = true,
        desktopFacebook: Boolean = false
    ) {
        val webView = createWebView()
        val tab = BrowserTab(webView)
        tabs.add(tab)
        binding.webContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        webView.visibility = if (select) View.VISIBLE else View.GONE

        if (desktopFacebook || isFacebookMessagesUrl(url)) {
            tab.desktopFacebook = true
            webView.settings.userAgentString = DESKTOP_USER_AGENT
        }
        webView.loadUrl(url)

        if (select) {
            selectTab(tabs.lastIndex)
        } else {
            updateTabButton()
        }
    }

    private fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        currentTabIndex = index

        tabs.forEachIndexed { tabIndex, tab ->
            val selected = tabIndex == index
            tab.webView.visibility = if (selected) View.VISIBLE else View.GONE
            if (selected) tab.webView.onResume() else tab.webView.onPause()
        }

        val webView = tabs[index].webView
        binding.progressBar.progress = webView.progress
        binding.progressBar.isVisible = webView.progress < 100
        updateAddressBar(webView.url)
        updateNavigationButtons()
        updateTabButton()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return

        if (tabs.size == 1) {
            val oldTab = tabs.removeAt(0)
            binding.webContainer.removeView(oldTab.webView)
            oldTab.webView.destroy()
            currentTabIndex = 0
            addTab(HOME_URL)
            return
        }

        val wasCurrent = index == currentTabIndex
        val removed = tabs.removeAt(index)
        binding.webContainer.removeView(removed.webView)
        removed.webView.destroy()

        when {
            wasCurrent -> currentTabIndex = index.coerceAtMost(tabs.lastIndex)
            index < currentTabIndex -> currentTabIndex--
        }
        selectTab(currentTabIndex)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val webView = WebView(this)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = mobileUserAgent
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, isNightMode())
        }
        webView.setBackgroundColor(
            if (isNightMode()) Color.parseColor("#0F1216") else Color.WHITE
        )

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase() ?: return false
                val tab = tabFor(view)

                // While Facebook is shown in desktop mode, ignore its attempts to
                // hand the page over to the Facebook/Messenger app. Keeping the
                // desktop page open is what lets Messages work inside the browser.
                if (tab?.desktopFacebook == true && isFacebookAppUri(url)) {
                    return true
                }

                return when (scheme) {
                    "http", "https", "about", "data" -> {
                        if (isFacebookMessagesUrl(url.toString())) {
                            if (!(tab?.desktopFacebook == true && isFacebookUrl(view.url))) {
                                openFacebookWeb(view, url.toString())
                                return true
                            }
                        } else if (tab?.desktopFacebook == true &&
                            url.host != null &&
                            !isFacebookHost(url.host)
                        ) {
                            restoreMobileUserAgent(view)
                        }
                        false
                    }
                    "file", "javascript" -> true
                    "fb", "fbconnect", "facebook" -> {
                        openFacebookWeb(view, facebookWebFallback(url))
                        true
                    }
                    "fb-messenger", "messenger" -> {
                        openFacebookWeb(view, MESSENGER_URL)
                        true
                    }
                    "intent" -> {
                        openIntentUri(view, url)
                        true
                    }
                    else -> {
                        openExternal(view, url)
                        true
                    }
                }
            }

            override fun onPageStarted(
                view: WebView,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                val tab = tabFor(view)
                if (tab?.desktopFacebook == true && url != null && !isFacebookUrl(url)) {
                    restoreMobileUserAgent(view)
                }
                if (isCurrent(view)) {
                    binding.progressBar.isVisible = true
                    updateAddressBar(url)
                    updateNavigationButtons()
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (isCurrent(view)) {
                    binding.progressBar.isVisible = false
                    updateAddressBar(url)
                    updateNavigationButtons()
                }
                if (url != null && isHome(url)) {
                    syncHomeTheme(view)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (isCurrent(view)) {
                    updateNavigationButtons()
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame && isCurrent(view)) {
                    binding.progressBar.isVisible = false
                    Toast.makeText(
                        this@MainActivity,
                        "Gagal memuat halaman. Periksa koneksi internet.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                // Never bypass a bad certificate.
                handler.cancel()
                Toast.makeText(
                    this@MainActivity,
                    "Koneksi tidak aman — halaman dibatalkan.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (isCurrent(view)) {
                    binding.progressBar.progress = newProgress
                    binding.progressBar.isVisible = newProgress < 100
                }
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                return try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    fileChooserCallback = null
                    Toast.makeText(
                        this@MainActivity,
                        "Tidak ada aplikasi untuk memilih file.",
                        Toast.LENGTH_SHORT
                    ).show()
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // Camera/microphone access from web pages is denied by default.
                request.deny()
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            downloadFile(
                url,
                userAgent ?: webView.settings.userAgentString,
                contentDisposition,
                mimeType
            )
        }

        return webView
    }

    private fun setupToolbar() {
        binding.btnGo.setOnClickListener {
            navigate(binding.addressBar.text.toString())
        }

        binding.addressBar.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event != null &&
                event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                navigate(binding.addressBar.text.toString())
                true
            } else {
                false
            }
        }

        binding.btnBack.setOnClickListener {
            if (currentWebView.canGoBack()) currentWebView.goBack()
        }
        binding.btnForward.setOnClickListener {
            if (currentWebView.canGoForward()) currentWebView.goForward()
        }
        binding.btnReload.setOnClickListener {
            if (currentWebView.url == null) {
                currentWebView.loadUrl(HOME_URL)
            } else {
                currentWebView.reload()
            }
        }
        binding.btnHome.setOnClickListener {
            restoreMobileUserAgent(currentWebView)
            currentWebView.loadUrl(HOME_URL)
        }
        binding.btnTabs.setOnClickListener {
            showTabsDialog()
        }
        binding.btnTabs.setOnLongClickListener {
            addTab(HOME_URL)
            true
        }
        binding.btnDownloads.setOnClickListener {
            showDownloadsDialog()
        }
        binding.btnTheme.setOnClickListener {
            toggleTheme()
        }
        binding.btnTheme.setOnLongClickListener {
            useSystemTheme()
            true
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (tabs.isEmpty()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    return
                }
                if (currentWebView.canGoBack()) {
                    currentWebView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun navigate(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return

        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            text.contains(" ") -> googleSearch(text)
            text.contains(".") -> "https://$text"
            else -> googleSearch(text)
        }

        val webView = currentWebView
        if (isFacebookMessagesUrl(url)) {
            openFacebookWeb(webView, url)
        } else {
            if (!isFacebookUrl(url)) {
                restoreMobileUserAgent(webView)
            }
            webView.loadUrl(url)
        }
        binding.addressBar.clearFocus()
        hideKeyboard()
    }

    private fun googleSearch(query: String): String =
        "https://www.google.com/search?q=" + Uri.encode(query)

    private fun updateAddressBar(url: String?) {
        if (binding.addressBar.hasFocus()) return
        if (url == null || isHome(url)) {
            binding.addressBar.setText("")
        } else {
            binding.addressBar.setText(url)
        }
    }

    private fun isHome(url: String): Boolean = url.startsWith(ASSET_BASE)

    private fun updateNavigationButtons() {
        if (tabs.isEmpty()) return
        binding.btnBack.isEnabled = currentWebView.canGoBack()
        binding.btnForward.isEnabled = currentWebView.canGoForward()
        binding.btnBack.alpha = if (binding.btnBack.isEnabled) 1f else 0.35f
        binding.btnForward.alpha = if (binding.btnForward.isEnabled) 1f else 0.35f
    }

    private fun updateTabButton() {
        binding.tvTabCount.text = tabs.size.toString()
        binding.btnTabs.contentDescription = getString(R.string.tabs_count, tabs.size)
    }

    private fun showTabsDialog() {
        tabsDialog?.dismiss()
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_tabs, null)
        val list = view.findViewById<LinearLayout>(R.id.tabList)

        tabs.forEachIndexed { index, tab ->
            val item = layoutInflater.inflate(R.layout.item_tab, list, false)
            val title = item.findViewById<TextView>(R.id.tabTitle)
            val urlView = item.findViewById<TextView>(R.id.tabUrl)
            val active = item.findViewById<TextView>(R.id.tabActive)
            val close = item.findViewById<ImageButton>(R.id.btnCloseTab)
            val webView = tab.webView

            title.text = webView.title?.takeIf { it.isNotBlank() }
                ?: getString(R.string.new_tab)
            urlView.text = webView.url ?: getString(R.string.new_tab)
            active.isVisible = index == currentTabIndex

            item.setOnClickListener {
                selectTab(index)
                dialog.dismiss()
            }
            close.setOnClickListener {
                dialog.dismiss()
                closeTab(index)
                showTabsDialog()
            }
            list.addView(item)
        }

        view.findViewById<Button>(R.id.btnNewTab).setOnClickListener {
            dialog.dismiss()
            addTab(HOME_URL)
        }

        dialog.setContentView(view)
        dialog.setOnShowListener {
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.setOnDismissListener {
            if (tabsDialog === dialog) tabsDialog = null
        }
        tabsDialog = dialog
        dialog.show()
    }

    private fun showDownloadsDialog() {
        downloadsDialog?.dismiss()
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_downloads, null)
        val listView = view.findViewById<ListView>(R.id.downloadList)
        val emptyView = view.findViewById<TextView>(R.id.downloadEmpty)

        val adapter = DownloadListAdapter(this, ::openDownload, ::confirmDeleteDownload)
        downloadsAdapter = adapter
        listView.adapter = adapter
        listView.emptyView = emptyView

        view.findViewById<Button>(R.id.btnOpenDownloadsFolder).setOnClickListener {
            openDownloadsFolder()
        }

        dialog.setContentView(view)
        dialog.setOnShowListener {
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.setOnDismissListener {
            if (downloadsDialog === dialog) {
                downloadsHandler.removeCallbacks(downloadsRefreshRunnable)
                downloadsDialog = null
                downloadsAdapter = null
            }
        }
        downloadsDialog = dialog
        dialog.show()
        refreshDownloadList()
        downloadsHandler.post(downloadsRefreshRunnable)
    }

    private fun refreshDownloadList() {
        downloadsAdapter?.update(queryDownloads())
    }

    private fun queryDownloads(): List<DownloadItem> {
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val items = mutableListOf<DownloadItem>()

        try {
            manager.query(DownloadManager.Query()).use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val titleIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                val statusIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val downloadedIndex =
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIndex =
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val mimeIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)
                val reasonIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)

                while (cursor.moveToNext()) {
                    items.add(
                        DownloadItem(
                            id = cursor.getLong(idIndex),
                            title = cursor.getString(titleIndex) ?: "",
                            status = cursor.getInt(statusIndex),
                            bytesDownloaded = cursor.getLong(downloadedIndex),
                            totalBytes = cursor.getLong(totalIndex),
                            mimeType = cursor.getString(mimeIndex),
                            reason = cursor.getInt(reasonIndex)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }

        return items.sortedByDescending { it.id }
    }

    private fun openDownload(item: DownloadItem) {
        if (item.status != DownloadManager.STATUS_SUCCESSFUL) {
            Toast.makeText(this, R.string.download_not_finished, Toast.LENGTH_SHORT).show()
            return
        }

        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val uri = try {
            manager.getUriForDownloadedFile(item.id)
        } catch (e: Exception) {
            null
        }
        if (uri == null) {
            Toast.makeText(this, R.string.download_missing, Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType?.takeIf { it.isNotBlank() } ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.download_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteDownload(item: DownloadItem) {
        val displayName = item.title.ifBlank { getString(R.string.download_untitled) }
        AlertDialog.Builder(this)
            .setTitle(R.string.download_delete_title)
            .setMessage(getString(R.string.download_delete_message, displayName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.download_delete_confirm) { _, _ ->
                val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                manager.remove(item.id)
                refreshDownloadList()
            }
            .show()
    }

    private fun openDownloadsFolder() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.download_folder_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadFile(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent ?: mobileUserAgent)
                CookieManager.getInstance().getCookie(url)?.let { cookie ->
                    addRequestHeader("Cookie", cookie)
                }
                setTitle(fileName)
                setDescription("Mengunduh melalui Browdika…")
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    fileName
                )
            }

            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, "Mengunduh: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Gagal mengunduh: ${e.message ?: "kesalahan tidak diketahui"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openExternal(webView: WebView, uri: Uri, fallbackUrl: String? = null) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            if (fallbackUrl != null) {
                loadFallback(webView, fallbackUrl)
            } else {
                showNoApp()
            }
        }
    }

    private fun openIntentUri(webView: WebView, uri: Uri) {
        val tab = tabFor(webView)
        if (tab?.desktopFacebook == true && isFacebookAppUri(uri)) return

        try {
            val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                val fallback = intent.getStringExtra("browser_fallback_url")
                val data = intent.data
                when {
                    !fallback.isNullOrBlank() -> {
                        if (!handleSchemeFallback(webView, Uri.parse(fallback))) showNoApp()
                    }
                    data != null -> {
                        if (!handleSchemeFallback(webView, data)) showNoApp()
                    }
                    else -> showNoApp()
                }
            }
        } catch (e: Exception) {
            showNoApp()
        }
    }

    private fun handleSchemeFallback(webView: WebView, uri: Uri): Boolean {
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val url = uri.toString()
                if (isFacebookUrl(url)) {
                    openFacebookWeb(webView, url)
                } else {
                    loadFallback(webView, url)
                }
                true
            }
            "fb", "fbconnect", "facebook" -> {
                openFacebookWeb(webView, facebookWebFallback(uri))
                true
            }
            "fb-messenger", "messenger" -> {
                openFacebookWeb(webView, MESSENGER_URL)
                true
            }
            "intent" -> {
                openIntentUri(webView, uri)
                true
            }
            else -> false
        }
    }

    private fun facebookWebFallback(uri: Uri): String {
        val target = ((uri.host ?: "") + "/" + (uri.path ?: "")).lowercase()
        return when {
            "messag" in target || "thread" in target ->
                "https://www.facebook.com/messages"
            "notification" in target ->
                "https://www.facebook.com/notifications"
            "profile" in target -> {
                val id = uri.lastPathSegment
                if (!id.isNullOrBlank()) {
                    "https://www.facebook.com/profile.php?id=$id"
                } else {
                    "https://www.facebook.com/profile.php"
                }
            }
            "friend" in target ->
                "https://www.facebook.com/friends"
            "group" in target ->
                "https://www.facebook.com/groups"
            "video" in target || "watch" in target ->
                "https://www.facebook.com/watch"
            else -> "https://www.facebook.com/"
        }
    }

    private fun openFacebookWeb(webView: WebView, url: String) {
        val tab = tabFor(webView) ?: return
        tab.desktopFacebook = true
        webView.settings.userAgentString = DESKTOP_USER_AGENT
        webView.loadUrl(url)
    }

    private fun restoreMobileUserAgent(webView: WebView) {
        val tab = tabFor(webView) ?: return
        if (!tab.desktopFacebook) return
        tab.desktopFacebook = false
        webView.settings.userAgentString = mobileUserAgent
    }

    private fun isFacebookHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val value = host.lowercase()
        return value == "facebook.com" ||
            value.endsWith(".facebook.com") ||
            value == "fb.com" ||
            value.endsWith(".fb.com") ||
            value == "messenger.com" ||
            value.endsWith(".messenger.com") ||
            value == "m.me"
    }

    private fun isFacebookUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            isFacebookHost(Uri.parse(url).host)
        } catch (e: Exception) {
            false
        }
    }

    private fun isFacebookMessagesUrl(url: String): Boolean {
        if (!isFacebookUrl(url)) return false
        val path = Uri.parse(url).path?.lowercase() ?: return false
        return path.startsWith("/messages") || path.startsWith("/thread")
    }

    private fun isFacebookAppUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme in setOf("fb", "fbconnect", "facebook", "fb-messenger", "messenger")) {
            return true
        }
        if (scheme != "intent") return false
        val raw = uri.toString().lowercase()
        return raw.contains("facebook") ||
            raw.contains("katana") ||
            raw.contains("orca") ||
            raw.contains("messenger") ||
            raw.contains("scheme=fb")
    }

    private fun loadFallback(webView: WebView, url: String) {
        val now = SystemClock.elapsedRealtime()
        if (now < fallbackBlockedUntil) return
        fallbackBlockedUntil = now + FALLBACK_GUARD_MS
        webView.loadUrl(url)
    }

    private fun tabFor(webView: WebView): BrowserTab? =
        tabs.firstOrNull { it.webView === webView }

    private fun isCurrent(webView: WebView): Boolean =
        tabs.isNotEmpty() && webView === currentWebView

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun updateThemeButton() {
        val dark = isNightMode()
        binding.btnTheme.setImageResource(
            if (dark) R.drawable.ic_light_mode else R.drawable.ic_dark_mode
        )
        binding.btnTheme.contentDescription = getString(
            if (dark) R.string.switch_to_light else R.string.switch_to_dark
        )
    }

    private fun toggleTheme() {
        val next = if (isNightMode()) {
            BrowdikaApp.THEME_LIGHT
        } else {
            BrowdikaApp.THEME_DARK
        }
        getSharedPreferences(BrowdikaApp.PREFS, MODE_PRIVATE)
            .edit()
            .putString(BrowdikaApp.KEY_THEME, next)
            .apply()
        AppCompatDelegate.setDefaultNightMode(
            if (next == BrowdikaApp.THEME_DARK) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )
    }

    private fun useSystemTheme() {
        getSharedPreferences(BrowdikaApp.PREFS, MODE_PRIVATE)
            .edit()
            .putString(BrowdikaApp.KEY_THEME, BrowdikaApp.THEME_SYSTEM)
            .apply()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        Toast.makeText(this, R.string.theme_follow_system, Toast.LENGTH_SHORT).show()
    }

    private fun syncHomeTheme(webView: WebView) {
        val theme = if (isNightMode()) "dark" else "light"
        webView.evaluateJavascript(
            "document.documentElement.dataset.theme='$theme';",
            null
        )
    }

    private fun showNoApp() {
        Toast.makeText(
            this,
            "Tidak ada aplikasi untuk membuka tautan ini.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.addressBar.windowToken, 0)
    }

    private fun requestLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                LEGACY_STORAGE_REQUEST
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val data = intent.data ?: return
        if (data.scheme == "http" || data.scheme == "https") {
            val webView = currentWebView
            if (!isFacebookUrl(data.toString())) {
                restoreMobileUserAgent(webView)
            }
            webView.loadUrl(data.toString())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val urls = tabs.map { it.webView.url ?: HOME_URL }
        outState.putStringArray(KEY_TAB_URLS, urls.toTypedArray())
        outState.putBooleanArray(
            KEY_TAB_DESKTOP,
            tabs.map { it.desktopFacebook }.toBooleanArray()
        )
        outState.putInt(KEY_CURRENT_TAB, currentTabIndex)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        downloadsHandler.removeCallbacks(downloadsRefreshRunnable)
        CookieManager.getInstance().flush()
        tabs.forEach { it.webView.onPause() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        tabs.forEach { it.webView.onResume() }
        updateThemeButton()
        if (downloadsDialog != null) {
            refreshDownloadList()
            downloadsHandler.post(downloadsRefreshRunnable)
        }
    }

    override fun onDestroy() {
        downloadsHandler.removeCallbacks(downloadsRefreshRunnable)
        downloadsDialog?.dismiss()
        tabsDialog?.dismiss()
        tabs.forEach {
            binding.webContainer.removeView(it.webView)
            it.webView.destroy()
        }
        tabs.clear()
        super.onDestroy()
    }

    companion object {
        private const val ASSET_BASE = "https://appassets.androidplatform.net/assets/"
        private const val HOME_URL = ASSET_BASE + "home.html"
        private const val MESSENGER_URL = "https://www.facebook.com/messages"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val FALLBACK_GUARD_MS = 5000L
        private const val LEGACY_STORAGE_REQUEST = 100
        private const val KEY_TAB_URLS = "tab_urls"
        private const val KEY_TAB_DESKTOP = "tab_desktop"
        private const val KEY_CURRENT_TAB = "current_tab"
    }
}
