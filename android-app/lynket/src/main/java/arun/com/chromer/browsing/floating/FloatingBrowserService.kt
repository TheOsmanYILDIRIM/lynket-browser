/*
 * Lynket
 * Termux:Float-style resizable browser overlay.
 */
package arun.com.chromer.browsing.floating

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import arun.com.chromer.R
import arun.com.chromer.browsing.webview.WebViewConfigurator
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min

class FloatingBrowserService : Service() {

  companion object {
    private const val ACTION_OPEN = "arun.com.chromer.action.FLOATING_BROWSER_OPEN"
    private const val EXTRA_URL = "floating_browser_url"
    private const val CHANNEL_ID = "lynket_floating_browser"
    private const val NOTIFICATION_ID = 4242
    private const val PREFS = "floating_browser_window"

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
  private var root: LinearLayout? = null
  private var webView: WebView? = null
  private var titleView: TextView? = null
  private var titleBarView: View? = null
  private var resizeBarView: View? = null
  private var layoutParams: WindowManager.LayoutParams? = null
  private var currentUrl: String? = null
  private var minimized = false
  private var expandedX = 0
  private var expandedY = 0
  private var expandedWidth = 0
  private var expandedHeight = 0

  private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
  private val density by lazy { resources.displayMetrics.density }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    startForeground(NOTIFICATION_ID, buildNotification())
    windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

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
      return
    }

    createWindow()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startForeground(NOTIFICATION_ID, buildNotification())
    if (root == null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))) {
      createWindow()
    }

    if (intent?.action == ACTION_OPEN) {
      intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }?.let(::loadUrl)
    }
    return START_STICKY
  }

  override fun onDestroy() {
    destroyWindow()
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
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_chromer_notification)
      .setContentTitle("Lynket Float")
      .setContentText("Floating browser is active")
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
  private fun createWindow() {
    if (root != null) return

    val displayWidth = resources.displayMetrics.widthPixels
    val displayHeight = resources.displayMetrics.heightPixels
    val minWidth = dp(240)
    val minHeight = dp(280)
    val defaultWidth = (displayWidth * 0.86f).toInt()
    val defaultHeight = (displayHeight * 0.56f).toInt()

    val width = prefs.getInt("width", defaultWidth).coerceIn(minWidth, displayWidth)
    val height = prefs.getInt("height", defaultHeight).coerceIn(minHeight, displayHeight)
    val x = prefs.getInt("x", dp(16)).coerceIn(0, max(0, displayWidth - width))
    val y = prefs.getInt("y", dp(72)).coerceIn(0, max(0, displayHeight - height))

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
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.RGBA_8888
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      this.x = x
      this.y = y
      softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }
    layoutParams = params

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

    titleBarView = titleBar

    val title = TextView(this).apply {
      text = "Lynket Float"
      setTextColor(Color.WHITE)
      textSize = 13f
      maxLines = 1
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(4), 0, dp(8), 0)
    }
    titleView = title
    titleBar.addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))

    fun control(label: String, action: () -> Unit): Button = Button(this).apply {
      text = label
      textSize = 14f
      setMinWidth(0)
      setMinimumWidth(0)
      setPadding(0, 0, 0, 0)
      setOnClickListener { action() }
    }

    val back = control("‹") { webView?.let { if (it.canGoBack()) it.goBack() } }
    val reload = control("↻") { webView?.reload() }
    val minimize = control("—") { minimizeToLiveBubble() }
    val close = control("×") { stopSelf() }
    titleBar.addView(back, LinearLayout.LayoutParams(dp(42), dp(44)))
    titleBar.addView(reload, LinearLayout.LayoutParams(dp(42), dp(44)))
    titleBar.addView(minimize, LinearLayout.LayoutParams(dp(42), dp(44)))
    titleBar.addView(close, LinearLayout.LayoutParams(dp(42), dp(44)))

    container.addView(titleBar, LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT,
      dp(44)
    ))

    val browser = createWebView()
    webView = browser
    container.addView(browser, LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT,
      0,
      1f
    ))

    val resizeBar = LinearLayout(this).apply {
      gravity = Gravity.END or Gravity.CENTER_VERTICAL
      setBackgroundColor(Color.rgb(32, 33, 36))
    }
    resizeBarView = resizeBar
    val resizeHint = TextView(this).apply {
      text = "↘"
      textSize = 22f
      setTextColor(Color.LTGRAY)
      gravity = Gravity.CENTER
    }
    resizeBar.addView(resizeHint, LinearLayout.LayoutParams(dp(48), dp(34)))
    container.addView(resizeBar, LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT,
      dp(34)
    ))

    installDragHandler(title)
    installResizeHandler(resizeHint, minWidth, minHeight, displayWidth, displayHeight)

    root = container
    try {
      windowManager.addView(container, params)
    } catch (error: Exception) {
      Timber.e(error, "Unable to attach floating browser window")
      root = null
      webView = null
      stopSelf()
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun createWebView(): WebView {
    return WebView(this).apply {
      setBackgroundColor(Color.rgb(18, 18, 18))
      WebViewConfigurator.configure(settings)
      settings.mediaPlaybackRequiresUserGesture = false
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
      }

      CookieManager.getInstance().setAcceptCookie(true)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
      }

      webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
          return handleNavigation(request?.url)
        }

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
          return handleNavigation(url?.let(Uri::parse))
        }

        override fun onRenderProcessGone(
          view: WebView?,
          detail: RenderProcessGoneDetail?
        ): Boolean {
          val url = view?.url ?: currentUrl
          Timber.e(
            "Floating WebView renderer gone; didCrash=%s priority=%s",
            detail?.didCrash(),
            detail?.rendererPriorityAtExit()
          )
          replaceDeadWebView(url)
          return true
        }
      }

      webChromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView?, title: String?) {
          super.onReceivedTitle(view, title)
          if (!title.isNullOrBlank()) titleView?.text = title
        }
      }
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

  private fun replaceDeadWebView(url: String?) {
    val container = root ?: return
    val old = webView
    val index = old?.let(container::indexOfChild) ?: 1
    if (old != null) {
      container.removeView(old)
      old.destroy()
    }
    val replacement = createWebView()
    webView = replacement
    container.addView(
      replacement,
      index.coerceAtLeast(1),
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
    )
    url?.let(::loadUrl)
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun installDragHandler(handle: View) {
    handle.setOnTouchListener(object : View.OnTouchListener {
      var startX = 0
      var startY = 0
      var touchX = 0f
      var touchY = 0f

      override fun onTouch(v: View?, event: MotionEvent): Boolean {
        val params = layoutParams ?: return false
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
            params.x = min(
              max(0, displayWidth - params.width),
              max(0, startX + (event.rawX - touchX).toInt())
            )
            params.y = min(
              max(0, displayHeight - params.height),
              max(0, startY + (event.rawY - touchY).toInt())
            )
            root?.let { windowManager.updateViewLayout(it, params) }
            return true
          }
          MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            saveGeometry()
            return true
          }
        }
        return false
      }
    })
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun installResizeHandler(
    handle: View,
    minWidth: Int,
    minHeight: Int,
    displayWidth: Int,
    displayHeight: Int
  ) {
    handle.setOnTouchListener(object : View.OnTouchListener {
      var startWidth = 0
      var startHeight = 0
      var touchX = 0f
      var touchY = 0f

      override fun onTouch(v: View?, event: MotionEvent): Boolean {
        val params = layoutParams ?: return false
        when (event.actionMasked) {
          MotionEvent.ACTION_DOWN -> {
            startWidth = params.width
            startHeight = params.height
            touchX = event.rawX
            touchY = event.rawY
            return true
          }
          MotionEvent.ACTION_MOVE -> {
            params.width = (startWidth + (event.rawX - touchX).toInt())
              .coerceIn(minWidth, max(minWidth, displayWidth - params.x))
            params.height = (startHeight + (event.rawY - touchY).toInt())
              .coerceIn(minHeight, max(minHeight, displayHeight - params.y))
            root?.let { windowManager.updateViewLayout(it, params) }
            return true
          }
          MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            saveGeometry()
            return true
          }
        }
        return false
      }
    })
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun minimizeToLiveBubble() {
    if (minimized) return
    val params = layoutParams ?: return
    val container = root ?: return

    expandedX = params.x
    expandedY = params.y
    expandedWidth = params.width
    expandedHeight = params.height
    saveGeometry()

    minimized = true
    titleBarView?.visibility = View.GONE
    resizeBarView?.visibility = View.GONE

    // Keep the actual WebView attached and visible: the 64dp window is a live thumbnail,
    // so JS/network activity is not intentionally paused while the user works in another app.
    val bubbleSize = dp(64)
    params.width = bubbleSize
    params.height = bubbleSize
    params.x = min(params.x, max(0, resources.displayMetrics.widthPixels - bubbleSize))
    params.y = min(params.y, max(0, resources.displayMetrics.heightPixels - bubbleSize))
    windowManager.updateViewLayout(container, params)

    container.alpha = 0.92f
    val miniTouchListener = createMiniBubbleTouchListener()
    container.setOnTouchListener(miniTouchListener)
    // WebView consumes touch events itself, so the listener must also sit on the live WebView.
    // This keeps the renderer visible/alive while making tap-to-restore and drag reliable.
    webView?.setOnTouchListener(miniTouchListener)
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun createMiniBubbleTouchListener(): View.OnTouchListener {
    val touchSlop = dp(8)
    var startX = 0
    var startY = 0
    var downRawX = 0f
    var downRawY = 0f
    var moved = false

    return View.OnTouchListener { _, event ->
      val params = layoutParams ?: return@OnTouchListener false
      val container = root ?: return@OnTouchListener false
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
          if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
            moved = true
          }
          if (moved) {
            val displayWidth = resources.displayMetrics.widthPixels
            val displayHeight = resources.displayMetrics.heightPixels
            params.x = (startX + dx).coerceIn(0, max(0, displayWidth - params.width))
            params.y = (startY + dy).coerceIn(0, max(0, displayHeight - params.height))
            windowManager.updateViewLayout(container, params)
          }
          true
        }

        MotionEvent.ACTION_UP -> {
          if (moved) {
            // Preserve the dragged bubble position as the next expanded-window anchor.
            expandedX = params.x
            expandedY = params.y
          } else {
            restoreFromLiveBubble()
          }
          true
        }

        MotionEvent.ACTION_CANCEL -> true
        else -> true
      }
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun restoreFromLiveBubble() {
    if (!minimized) return
    val params = layoutParams ?: return
    val container = root ?: return

    minimized = false
    container.setOnTouchListener(null)
    webView?.setOnTouchListener(null)
    container.alpha = 1f
    titleBarView?.visibility = View.VISIBLE
    resizeBarView?.visibility = View.VISIBLE
    params.width = expandedWidth.coerceAtLeast(dp(240))
    params.height = expandedHeight.coerceAtLeast(dp(280))
    val displayWidth = resources.displayMetrics.widthPixels
    val displayHeight = resources.displayMetrics.heightPixels
    params.x = expandedX.coerceIn(0, max(0, displayWidth - params.width))
    params.y = expandedY.coerceIn(0, max(0, displayHeight - params.height))
    windowManager.updateViewLayout(container, params)
    saveGeometry()
  }

  private fun saveGeometry() {
    val params = layoutParams ?: return
    prefs.edit()
      .putInt("x", params.x)
      .putInt("y", params.y)
      .putInt("width", params.width)
      .putInt("height", params.height)
      .apply()
  }

  private fun loadUrl(url: String) {
    currentUrl = url
    titleView?.text = Uri.parse(url).host ?: "Lynket Float"
    webView?.loadUrl(url)
  }

  private fun destroyWindow() {
    val container = root
    if (container != null) {
      try {
        windowManager.removeView(container)
      } catch (error: Exception) {
        Timber.e(error, "Unable to remove floating browser window")
      }
    }
    webView?.apply {
      stopLoading()
      loadUrl("about:blank")
      clearHistory()
      removeAllViews()
      destroy()
    }
    webView = null
    root = null
    titleBarView = null
    resizeBarView = null
    layoutParams = null
    minimized = false
  }

  private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
