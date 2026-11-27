/*
 * Lynket
 * Termux:Float-style multi-window browser overlay.
 */
package arun.com.chromer.browsing.floating

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Environment
import android.provider.Settings
import android.view.ActionMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebView.WebViewTransport
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.URLUtil
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import arun.com.chromer.R
import arun.com.chromer.browsing.webview.WebViewConfigurator
import arun.com.chromer.settings.Preferences
import timber.log.Timber
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FloatingBrowserService : Service() {

  companion object {
    private const val ACTION_OPEN = "arun.com.chromer.action.FLOATING_BROWSER_OPEN"
    private const val EXTRA_URL = "floating_browser_url"
    private const val CHANNEL_ID = "lynket_floating_browser"
    private const val NOTIFICATION_ID = 4242
    private const val PREFS = "floating_browser_window"
    private const val BUBBLE_SIZE_DP = 64
    private val nextWindowId = AtomicLong(1L)
    private val nextFileChooserId = AtomicLong(1L)
    private val fileChooserCallbacks = ConcurrentHashMap<Long, ValueCallback<Array<Uri>>>()

    internal fun deliverFileChooserResult(requestId: Long, uris: Array<Uri>?) {
      fileChooserCallbacks.remove(requestId)?.onReceiveValue(uris)
    }

    internal fun cancelFileChooser(requestId: Long) {
      fileChooserCallbacks.remove(requestId)?.onReceiveValue(null)
    }

    @JvmStatic
    fun open(context: Context, url: String) {
      val intent = Intent(context, FloatingBrowserService::class.java).apply {
        action = ACTION_OPEN
        putExtra(EXTRA_URL, url)
      }
      ContextCompat.startForegroundService(context, intent)
    }
  }

  private lateinit var windowManager: WindowManager
  private val windows = LinkedHashMap<Long, FloatingWindow>()
  private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
  private val density by lazy { resources.displayMetrics.density }
  private val defaultPreferences by lazy {
    Preferences.get(this).getDefaultSharedPreferences()
  }
  private val preferenceListener =
    SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
      if (key == Preferences.FLOATING_BROWSER_ZOOM) {
        windows.values.forEach { applyConfiguredZoom(it.webView, injectCss = true) }
      }
    }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    startForeground(NOTIFICATION_ID, buildNotification())
    windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    defaultPreferences.registerOnSharedPreferenceChangeListener(preferenceListener)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
      try {
        startActivity(
          Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
          ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
      } catch (error: Exception) {
        Timber.e(error, "Unable to open overlay permission settings")
      }
      stopSelf()
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startForeground(NOTIFICATION_ID, buildNotification())
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
      stopSelf()
      return START_NOT_STICKY
    }

    if (intent?.action == ACTION_OPEN) {
      intent.getStringExtra(EXTRA_URL)
        ?.takeIf { it.isNotBlank() }
        ?.let(::createWindow)
    }
    return START_STICKY
  }

  override fun onDestroy() {
    defaultPreferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    windows.values.toList().forEach { destroyWindow(it, stopWhenEmpty = false) }
    windows.clear()
    super.onDestroy()
  }

  private fun buildNotification(): Notification {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      manager.createNotificationChannel(
        NotificationChannel(
          CHANNEL_ID,
          "Floating browser",
          NotificationManager.IMPORTANCE_LOW
        )
      )
    }
    val count = windows.size
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_chromer_notification)
      .setContentTitle("Lynket Float")
      .setContentText(
        if (count <= 1) "Floating browser is active"
        else "$count floating browser windows are active"
      )
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  private class SelectionAwareWebView(context: Context) : WebView(context) {
    var selectionActionModeActive: Boolean = false
      private set

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? {
      return startSelectionActionMode(callback) { super.startActionMode(it) }
    }

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
      return startSelectionActionMode(callback) { super.startActionMode(it, type) }
    }

    private fun startSelectionActionMode(
      callback: ActionMode.Callback?,
      starter: (ActionMode.Callback?) -> ActionMode?
    ): ActionMode? {
      if (callback == null) return starter(null)
      selectionActionModeActive = true
      val wrapped = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: android.view.Menu?): Boolean =
          callback.onCreateActionMode(mode, menu)

        override fun onPrepareActionMode(mode: ActionMode?, menu: android.view.Menu?): Boolean =
          callback.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode?, item: android.view.MenuItem?): Boolean =
          callback.onActionItemClicked(mode, item)

        override fun onDestroyActionMode(mode: ActionMode?) {
          selectionActionModeActive = false
          callback.onDestroyActionMode(mode)
        }
      }
      val mode = starter(wrapped)
      if (mode == null) selectionActionModeActive = false
      return mode
    }
  }

  private data class FloatingWindow(
    val id: Long,
    val root: LinearLayout,
    val titleBar: View,
    val resizeBar: View,
    val titleView: TextView,
    var webView: WebView,
    val params: WindowManager.LayoutParams,
    var currentUrl: String,
    var minimized: Boolean = false,
    var expandedX: Int = 0,
    var expandedY: Int = 0,
    var expandedWidth: Int = 0,
    var expandedHeight: Int = 0
  )

  @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
  private fun createWindow(url: String) {
    val displayWidth = resources.displayMetrics.widthPixels
    val displayHeight = resources.displayMetrics.heightPixels
    val minWidth = dp(240)
    val minHeight = dp(280)
    val defaultWidth = (displayWidth * 0.86f).toInt()
    val defaultHeight = (displayHeight * 0.56f).toInt()

    val id = nextWindowId.getAndIncrement()
    val offset = ((windows.size % 5) * dp(28))
    val width = prefs.getInt("width", defaultWidth).coerceIn(minWidth, displayWidth)
    val height = prefs.getInt("height", defaultHeight).coerceIn(minHeight, displayHeight)
    val x = (prefs.getInt("x", dp(16)) + offset).coerceIn(0, max(0, displayWidth - width))
    val y = (prefs.getInt("y", dp(72)) + offset).coerceIn(0, max(0, displayHeight - height))

    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
      @Suppress("DEPRECATION")
      WindowManager.LayoutParams.TYPE_PHONE
    }

    val params = WindowManager.LayoutParams(
      width,
      height,
      type,
      WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.RGBA_8888
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      this.x = x
      this.y = y
      softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    val container = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(Color.rgb(24, 24, 24))
      elevation = dp(8).toFloat()
    }

    val titleBar = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(8), 0, dp(4), 0)
      setBackgroundColor(Color.rgb(32, 33, 36))
    }

    val providerLabel = currentWebViewProviderLabel()
    val title = TextView(this).apply {
      text = providerLabel
      setTextColor(Color.WHITE)
      textSize = 12f
      maxLines = 1
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(4), 0, dp(8), 0)
    }
    titleBar.addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))

    var session: FloatingWindow? = null

    fun control(label: String, action: () -> Unit): Button = Button(this).apply {
      text = label
      textSize = 14f
      setMinWidth(0)
      setMinimumWidth(0)
      setPadding(0, 0, 0, 0)
      setOnClickListener { action() }
    }

    val back = control("‹") {
      session?.webView?.let { if (it.canGoBack()) it.goBack() }
    }
    val reload = control("↻") {
      session?.webView?.reload()
    }
    val minimize = control("—") {
      session?.let(::minimizeToLiveBubble)
    }
    val close = control("×") {
      session?.let(::destroyWindow)
    }
    titleBar.addView(back, LinearLayout.LayoutParams(dp(42), dp(44)))
    titleBar.addView(reload, LinearLayout.LayoutParams(dp(42), dp(44)))
    titleBar.addView(minimize, LinearLayout.LayoutParams(dp(42), dp(44)))
    titleBar.addView(close, LinearLayout.LayoutParams(dp(42), dp(44)))
    container.addView(
      titleBar,
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
    )

    val browser = createWebView(
      onTitle = { pageTitle ->
        val current = session
        if (current != null && !pageTitle.isNullOrBlank()) {
          current.titleView.text = pageTitle
        }
      },
      onRendererGone = { deadView, deadUrl ->
        session?.let { replaceDeadWebView(it, deadView, deadUrl) }
      }
    )
    container.addView(
      browser,
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
    )

    val resizeBar = LinearLayout(this).apply {
      gravity = Gravity.END or Gravity.CENTER_VERTICAL
      setBackgroundColor(Color.rgb(32, 33, 36))
    }
    val resizeHint = TextView(this).apply {
      text = "↘"
      textSize = 22f
      setTextColor(Color.LTGRAY)
      gravity = Gravity.CENTER
    }
    resizeBar.addView(resizeHint, LinearLayout.LayoutParams(dp(48), dp(34)))
    container.addView(
      resizeBar,
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34))
    )

    val createdSession = FloatingWindow(
      id = id,
      root = container,
      titleBar = titleBar,
      resizeBar = resizeBar,
      titleView = title,
      webView = browser,
      params = params,
      currentUrl = url,
      expandedX = x,
      expandedY = y,
      expandedWidth = width,
      expandedHeight = height
    )

    session = createdSession
    installDragHandler(createdSession, title)
    installResizeHandler(createdSession, resizeHint, minWidth, minHeight)
    installExpandedInteraction(createdSession)
    installOutsideFocusRelease(createdSession)

    try {
      windowManager.addView(container, params)
      windows[id] = createdSession
      browser.loadUrl(url)
      updateNotification()
    } catch (error: Exception) {
      Timber.e(error, "Unable to attach floating browser window")
      browser.destroy()
      if (windows.isEmpty()) stopSelf()
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun createWebView(
    onTitle: (String?) -> Unit,
    onRendererGone: (WebView?, String?) -> Unit
  ): WebView {
    return SelectionAwareWebView(this).apply {
      setBackgroundColor(Color.rgb(18, 18, 18))
      WebViewConfigurator.configure(settings)
      settings.mediaPlaybackRequiresUserGesture = false
      settings.useWideViewPort = true
      settings.loadWithOverviewMode = false
      settings.setSupportZoom(true)
      settings.builtInZoomControls = true
      settings.displayZoomControls = false
      applyConfiguredZoom(this, injectCss = false)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
      }

      CookieManager.getInstance().setAcceptCookie(true)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
      }

      webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
          view: WebView?,
          request: WebResourceRequest?
        ): Boolean = handleNavigation(request?.url)

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
          return handleNavigation(url?.let(Uri::parse))
        }

        override fun onPageFinished(view: WebView?, url: String?) {
          super.onPageFinished(view, url)
          view?.let { applyConfiguredZoom(it, injectCss = true) }
          url?.let { finished ->
            windows.values.firstOrNull { it.webView === view }?.currentUrl = finished
          }
        }

        override fun onRenderProcessGone(
          view: WebView?,
          detail: RenderProcessGoneDetail?
        ): Boolean {
          Timber.e(
            "Floating WebView renderer gone; didCrash=%s priority=%s",
            detail?.didCrash(),
            detail?.rendererPriorityAtExit()
          )
          onRendererGone(view, view?.url)
          return true
        }
      }

      webChromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView?, title: String?) {
          super.onReceivedTitle(view, title)
          onTitle(title)
        }

        override fun onShowFileChooser(
          webView: WebView?,
          filePathCallback: ValueCallback<Array<Uri>>?,
          fileChooserParams: FileChooserParams?
        ): Boolean {
          if (filePathCallback == null || fileChooserParams == null) return false
          val requestId = nextFileChooserId.getAndIncrement()
          fileChooserCallbacks.put(requestId, filePathCallback)?.onReceiveValue(null)

          val chooserIntent = try {
            fileChooserParams.createIntent()
          } catch (error: Exception) {
            Timber.e(error, "Unable to create file chooser intent")
            fileChooserCallbacks.remove(requestId)?.onReceiveValue(null)
            return true
          }

          return try {
            startActivity(
              Intent(this@FloatingBrowserService, FloatingFileChooserActivity::class.java)
                .putExtra(FloatingFileChooserActivity.EXTRA_REQUEST_ID, requestId)
                .putExtra(FloatingFileChooserActivity.EXTRA_CHOOSER_INTENT, chooserIntent)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
          } catch (error: Exception) {
            Timber.e(error, "Unable to launch file chooser")
            fileChooserCallbacks.remove(requestId)?.onReceiveValue(null)
            true
          }
        }
      }

      setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
        enqueueDownload(downloadUrl, userAgent, contentDisposition, mimeType)
      }
    }
  }

  private fun enqueueDownload(
    url: String?,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?
  ) {
    if (url.isNullOrBlank()) return
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      Timber.w("Unsupported floating download URL: %s", url)
      return
    }

    try {
      val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
      val request = DownloadManager.Request(Uri.parse(url)).apply {
        setTitle(fileName)
        setDescription("Downloading from Lynket Float")
        setNotificationVisibility(
          DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
        )
        if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
        if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
        CookieManager.getInstance().getCookie(url)?.let { cookie ->
          if (cookie.isNotBlank()) addRequestHeader("Cookie", cookie)
        }
        addRequestHeader("Referer", url)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }
      }
      val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
      manager.enqueue(request)
    } catch (error: Exception) {
      Timber.e(error, "Floating browser download failed")
    }
  }

  private fun applyConfiguredZoom(webView: WebView, injectCss: Boolean) {
    val percent = Preferences.get(this)
      .floatingBrowserZoomPercent()
      .coerceIn(50, 100)

    // setInitialScale alone is commonly overridden by a page's viewport meta tag.
    // Keep it as an initial hint, then enforce a true whole-page Chromium CSS zoom
    // after load. This scales text, controls and layout together instead of text only.
    webView.setInitialScale(percent)
    if (injectCss) {
      webView.evaluateJavascript(
        "(function(){var e=document.documentElement;if(e){e.style.zoom='" +
          percent + "%';}})();",
        null
      )
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun installExpandedInteraction(session: FloatingWindow) {
    session.webView.setOnTouchListener { _, event ->
      if (event.actionMasked == MotionEvent.ACTION_DOWN) {
        setWindowFocusable(session, true)
      }
      false
    }
    session.webView.setOnKeyListener { _, keyCode, event ->
      if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
        if (session.webView.canGoBack()) {
          session.webView.goBack()
          true
        } else {
          false
        }
      } else {
        false
      }
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun installOutsideFocusRelease(session: FloatingWindow) {
    session.root.setOnTouchListener { _, event ->
      val selectionActive =
        (session.webView as? SelectionAwareWebView)?.selectionActionModeActive == true
      if (event.actionMasked == MotionEvent.ACTION_OUTSIDE &&
        !session.minimized &&
        !selectionActive
      ) {
        setWindowFocusable(session, false)
      }
      false
    }
  }

  private fun setWindowFocusable(session: FloatingWindow, focusable: Boolean) {
    val params = session.params
    val notFocusable = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    val isNotFocusable = params.flags and notFocusable != 0
    if (focusable == !isNotFocusable) return

    if (focusable) {
      params.flags = params.flags and notFocusable.inv()
      windowManager.updateViewLayout(session.root, params)
      session.webView.isFocusableInTouchMode = true
      session.webView.requestFocus()
    } else {
      session.webView.clearFocus()
      params.flags = params.flags or notFocusable
      windowManager.updateViewLayout(session.root, params)
      val inputMethodManager =
        getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
      inputMethodManager.hideSoftInputFromWindow(session.root.windowToken, 0)
    }
  }

  private fun currentWebViewProviderLabel(): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val provider = WebView.getCurrentWebViewPackage()
      provider?.packageName?.substringAfterLast('.') ?: "System WebView"
    } else {
      "System WebView"
    }
  }

  private fun handleNavigation(uri: Uri?): Boolean {
    uri ?: return false
    val scheme = uri.scheme?.lowercase()
    if (scheme == "http" || scheme == "https") return false
    return try {
      startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      true
    } catch (error: Exception) {
      Timber.e(error, "Unable to open external URL: %s", uri)
      true
    }
  }

  private fun replaceDeadWebView(session: FloatingWindow, deadView: WebView?, url: String?) {
    val index = session.root.indexOfChild(deadView).coerceAtLeast(1)
    deadView?.let {
      session.root.removeView(it)
      it.destroy()
    }
    val replacement = createWebView(
      onTitle = { title ->
        if (!title.isNullOrBlank()) session.titleView.text = title
      },
      onRendererGone = { view, deadUrl ->
        replaceDeadWebView(session, view, deadUrl)
      }
    )
    session.webView = replacement
    session.root.addView(
      replacement,
      index,
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
    )
    if (session.minimized) {
      val miniTouchListener = createMiniBubbleTouchListener(session)
      replacement.setOnTouchListener(miniTouchListener)
    } else {
      installExpandedInteraction(session)
    }
    replacement.loadUrl(url ?: session.currentUrl)
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun installDragHandler(session: FloatingWindow, handle: View) {
    handle.setOnTouchListener(object : View.OnTouchListener {
      var startX = 0
      var startY = 0
      var touchX = 0f
      var touchY = 0f

      override fun onTouch(v: View?, event: MotionEvent): Boolean {
        val params = session.params
        when (event.actionMasked) {
          MotionEvent.ACTION_DOWN -> {
            startX = params.x
            startY = params.y
            touchX = event.rawX
            touchY = event.rawY
            return true
          }
          MotionEvent.ACTION_MOVE -> {
            val displayWidth = resources.displayMetrics.widthPixels
            val displayHeight = resources.displayMetrics.heightPixels
            params.x = (startX + (event.rawX - touchX).toInt())
              .coerceIn(0, max(0, displayWidth - params.width))
            params.y = (startY + (event.rawY - touchY).toInt())
              .coerceIn(0, max(0, displayHeight - params.height))
            windowManager.updateViewLayout(session.root, params)
            return true
          }
          MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            saveGeometry(session)
            return true
          }
        }
        return false
      }
    })
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun installResizeHandler(
    session: FloatingWindow,
    handle: View,
    minWidth: Int,
    minHeight: Int
  ) {
    handle.setOnTouchListener(object : View.OnTouchListener {
      var startWidth = 0
      var startHeight = 0
      var touchX = 0f
      var touchY = 0f

      override fun onTouch(v: View?, event: MotionEvent): Boolean {
        val params = session.params
        when (event.actionMasked) {
          MotionEvent.ACTION_DOWN -> {
            startWidth = params.width
            startHeight = params.height
            touchX = event.rawX
            touchY = event.rawY
            return true
          }
          MotionEvent.ACTION_MOVE -> {
            val displayWidth = resources.displayMetrics.widthPixels
            val displayHeight = resources.displayMetrics.heightPixels
            params.width = (startWidth + (event.rawX - touchX).toInt())
              .coerceIn(minWidth, max(minWidth, displayWidth - params.x))
            params.height = (startHeight + (event.rawY - touchY).toInt())
              .coerceIn(minHeight, max(minHeight, displayHeight - params.y))
            windowManager.updateViewLayout(session.root, params)
            return true
          }
          MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            saveGeometry(session)
            return true
          }
        }
        return false
      }
    })
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun minimizeToLiveBubble(session: FloatingWindow) {
    if (session.minimized) return
    val params = session.params

    session.expandedX = params.x
    session.expandedY = params.y
    session.expandedWidth = params.width
    session.expandedHeight = params.height
    saveGeometry(session)

    session.minimized = true
    session.titleBar.visibility = View.GONE
    session.resizeBar.visibility = View.GONE
    setWindowFocusable(session, false)

    val bubbleSize = dp(BUBBLE_SIZE_DP)
    params.width = bubbleSize
    params.height = bubbleSize
    params.x = min(params.x, max(0, resources.displayMetrics.widthPixels - bubbleSize))
    params.y = min(params.y, max(0, resources.displayMetrics.heightPixels - bubbleSize))
    windowManager.updateViewLayout(session.root, params)

    // Keep the live WebView attached/rendering but make the tiny bubble unobtrusive.
    // Alpha changes composition only; it does not pause/detach the renderer.
    session.root.alpha = 0.35f
    val miniTouchListener = createMiniBubbleTouchListener(session)
    session.root.setOnTouchListener(miniTouchListener)
    session.webView.setOnTouchListener(miniTouchListener)
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun createMiniBubbleTouchListener(session: FloatingWindow): View.OnTouchListener {
    val touchSlop = dp(8)
    var startX = 0
    var startY = 0
    var downRawX = 0f
    var downRawY = 0f
    var moved = false

    return View.OnTouchListener { _, event ->
      val params = session.params
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          startX = params.x
          startY = params.y
          downRawX = event.rawX
          downRawY = event.rawY
          moved = false
          true
        }

        MotionEvent.ACTION_MOVE -> {
          val dx = (event.rawX - downRawX).toInt()
          val dy = (event.rawY - downRawY).toInt()
          if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) moved = true
          if (moved) {
            val displayWidth = resources.displayMetrics.widthPixels
            val displayHeight = resources.displayMetrics.heightPixels
            params.x = (startX + dx).coerceIn(0, max(0, displayWidth - params.width))
            params.y = (startY + dy).coerceIn(0, max(0, displayHeight - params.height))
            windowManager.updateViewLayout(session.root, params)
          }
          true
        }

        MotionEvent.ACTION_UP -> {
          if (moved) {
            session.expandedX = params.x
            session.expandedY = params.y
          } else {
            restoreFromLiveBubble(session)
          }
          true
        }

        MotionEvent.ACTION_CANCEL -> true
        else -> true
      }
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun restoreFromLiveBubble(session: FloatingWindow) {
    if (!session.minimized) return
    val params = session.params

    session.minimized = false
    session.root.setOnTouchListener(null)
    session.webView.setOnTouchListener(null)
    session.root.alpha = 1f
    session.titleBar.visibility = View.VISIBLE
    session.resizeBar.visibility = View.VISIBLE

    params.width = session.expandedWidth.coerceAtLeast(dp(240))
    params.height = session.expandedHeight.coerceAtLeast(dp(280))
    val displayWidth = resources.displayMetrics.widthPixels
    val displayHeight = resources.displayMetrics.heightPixels
    params.x = session.expandedX.coerceIn(0, max(0, displayWidth - params.width))
    params.y = session.expandedY.coerceIn(0, max(0, displayHeight - params.height))
    windowManager.updateViewLayout(session.root, params)
    setWindowFocusable(session, true)
    installExpandedInteraction(session)
    installOutsideFocusRelease(session)
    saveGeometry(session)
  }

  private fun saveGeometry(session: FloatingWindow) {
    val params = session.params
    if (!session.minimized) {
      prefs.edit()
        .putInt("x", params.x)
        .putInt("y", params.y)
        .putInt("width", params.width)
        .putInt("height", params.height)
        .apply()
    }
  }

  private fun destroyWindow(session: FloatingWindow, stopWhenEmpty: Boolean = true) {
    windows.remove(session.id)
    try {
      windowManager.removeView(session.root)
    } catch (error: Exception) {
      Timber.e(error, "Unable to remove floating browser window")
    }
    session.webView.apply {
      setOnTouchListener(null)
      stopLoading()
      loadUrl("about:blank")
      clearHistory()
      removeAllViews()
      destroy()
    }

    updateNotification()
    if (stopWhenEmpty && windows.isEmpty()) stopSelf()
  }

  private fun updateNotification() {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.notify(NOTIFICATION_ID, buildNotification())
  }

  private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
