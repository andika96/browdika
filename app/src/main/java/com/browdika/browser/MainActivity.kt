package com.browdika.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.webkit.WebViewAssetLoader
import com.browdika.browser.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

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

    @SuppressLint("SetJavaScriptEnabled")
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

        setupWebView()
        setupToolbar()
        setupBackNavigation()
        requestLegacyStoragePermission()

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
            val data = intent?.data
            if (data != null && (data.scheme == "http" || data.scheme == "https")) {
                binding.webView.loadUrl(data.toString())
            } else {
                binding.webView.loadUrl(HOME_URL)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView

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

            // Remove the Android WebView marker and identify the app instead.
            // This improves compatibility with sites that reject embedded WebViews.
            userAgentString = userAgentString.replace("; wv", "") + " Browdika/1.0"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

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

                return when (scheme) {
                    "http", "https", "about", "data" -> false
                    "file", "javascript" -> true
                    "intent" -> {
                        openIntentUri(url)
                        true
                    }
                    else -> {
                        openExternal(url)
                        true
                    }
                }
            }

            override fun onPageStarted(
                view: WebView,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                binding.progressBar.isVisible = true
                updateAddressBar(url)
                updateNavigationButtons()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                binding.progressBar.isVisible = false
                updateAddressBar(url)
                updateNavigationButtons()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                updateNavigationButtons()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
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
                binding.progressBar.progress = newProgress
                binding.progressBar.isVisible = newProgress < 100
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
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
            downloadFile(url, userAgent, contentDisposition, mimeType)
        }
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
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        binding.btnForward.setOnClickListener {
            if (binding.webView.canGoForward()) binding.webView.goForward()
        }
        binding.btnReload.setOnClickListener {
            binding.webView.reload()
        }
        binding.btnHome.setOnClickListener {
            binding.webView.loadUrl(HOME_URL)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
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

        binding.webView.loadUrl(url)
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
        binding.btnBack.isEnabled = binding.webView.canGoBack()
        binding.btnForward.isEnabled = binding.webView.canGoForward()
        binding.btnBack.alpha = if (binding.btnBack.isEnabled) 1f else 0.35f
        binding.btnForward.alpha = if (binding.btnForward.isEnabled) 1f else 0.35f
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
                addRequestHeader(
                    "User-Agent",
                    userAgent ?: binding.webView.settings.userAgentString
                )
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

    private fun openExternal(uri: Uri) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "Tidak ada aplikasi untuk membuka tautan ini.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun openIntentUri(uri: Uri) {
        try {
            val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            startActivity(intent)
        } catch (e: Exception) {
            openExternal(uri)
        }
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
            binding.webView.loadUrl(data.toString())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        binding.webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val ASSET_BASE = "https://appassets.androidplatform.net/assets/"
        private const val HOME_URL = ASSET_BASE + "home.html"
        private const val LEGACY_STORAGE_REQUEST = 100
    }
}
