/*
 *
 *  Lynket
 *
 *  Copyright (C) 2022 Arunkumar
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package arun.com.chromer.browsing.webview

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.InflateException
import android.view.ViewGroup
import android.view.WindowManager
import android.view.Menu
import android.view.MenuItem
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.ContextCompat
import arun.com.chromer.R
import arun.com.chromer.browsing.BrowsingActivity
import arun.com.chromer.browsing.EXTRA_CURRENT_LOADING_URL
import arun.com.chromer.browsing.menu.MenuDelegate
import arun.com.chromer.data.website.model.Website
import arun.com.chromer.di.activity.ActivityComponent
import arun.com.chromer.extenstions.applyColor
import arun.com.chromer.extenstions.setAutoHideProgress
import arun.com.chromer.settings.Preferences
import arun.com.chromer.shared.Constants
import arun.com.chromer.util.ColorUtil
import arun.com.chromer.util.Utils
import kotlinx.android.synthetic.main.activity_web_view.*
import kotlinx.android.synthetic.main.activity_web_view_content.*
import timber.log.Timber
import javax.inject.Inject

open class WebViewActivity : BrowsingActivity() {
  @Inject
  lateinit var menuDelegate: MenuDelegate

  private var themeColor = 0
  private var fgColorStateList: ColorStateList = ColorStateList.valueOf(0)
  private var foregroundColor = 0

  // Keep the live page attached to a tiny overlay while this Activity is backgrounded. Unlike a
  // second hidden WebView, this preserves the exact DOM/JS/WebSocket session the user is viewing.
  private var backgroundOverlayManager: WindowManager? = null
  private var originalWebViewParent: ViewGroup? = null
  private var originalWebViewIndex = -1
  private var originalWebViewLayoutParams: ViewGroup.LayoutParams? = null
  private var webViewAttachedToBackgroundOverlay = false

  // RAS-58: intercepts external-app links (non http(s) schemes / intent://) with a
  // confirmation dialog. Also covers EmbeddableWebViewActivity (bubbles) via inheritance.
  private val externalAppLaunchHandler by lazy {
    ExternalAppLaunchHandler(this, Preferences.get(this))
  }

  override fun inject(activityComponent: ActivityComponent) = activityComponent.inject(this)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setupToolbar()
    setupSwipeRefresh()
    setupWebView(savedInstanceState)
    setupBottomBar()
  }

  override fun getCurrentUrl(): String {
    return if (webView.url != null)
      webView.url!!
    else super.getCurrentUrl()
  }

  override fun onCreateOptionsMenu(menu: Menu) = menuDelegate.createOptionsMenu(menu)

  override fun onPrepareOptionsMenu(menu: Menu) = menuDelegate.prepareOptionsMenu(menu)

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return menuDelegate.handleItemSelected(item.itemId)
  }

  private fun setupBottomBar() {
    menuDelegate.setupBottombar(bottomNavigation)
  }

  override fun onStart() {
    super.onStart()
    restoreWebViewFromBackgroundOverlay()
  }

  override fun onStop() {
    if (!isFinishing && !isChangingConfigurations) {
      attachWebViewToBackgroundOverlay()
    }
    super.onStop()
  }

  override fun onDestroy() {
    detachWebViewFromBackgroundOverlay()
    webView.destroy()
    super.onDestroy()
  }

  private fun attachWebViewToBackgroundOverlay() {
    if (webViewAttachedToBackgroundOverlay || !Settings.canDrawOverlays(this)) return
    val parent = webView.parent as? ViewGroup ?: return
    try {
      originalWebViewParent = parent
      originalWebViewIndex = parent.indexOfChild(webView)
      originalWebViewLayoutParams = webView.layoutParams
      parent.removeView(webView)

      val manager = applicationContext.getSystemService(WINDOW_SERVICE) as WindowManager
      val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
      }
      val params = WindowManager.LayoutParams(
        1,
        1,
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
      ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
        alpha = 0.01f
      }
      manager.addView(webView, params)
      backgroundOverlayManager = manager
      webViewAttachedToBackgroundOverlay = true
      webView.onResume()
      Timber.d("Moved live WebView into 1x1 background keep-alive overlay")
    } catch (error: Exception) {
      Timber.e(error, "Failed to attach live WebView to background overlay")
      restoreWebViewAfterAttachFailure()
    }
  }

  private fun restoreWebViewFromBackgroundOverlay() {
    if (!webViewAttachedToBackgroundOverlay) return
    val parent = originalWebViewParent ?: return
    try {
      backgroundOverlayManager?.removeView(webView)
      webViewAttachedToBackgroundOverlay = false
      val params = originalWebViewLayoutParams
      val index = originalWebViewIndex.coerceIn(0, parent.childCount)
      if (params != null) {
        parent.addView(webView, index, params)
      } else {
        parent.addView(webView, index)
      }
      webView.onResume()
      Timber.d("Restored live WebView from background keep-alive overlay")
    } catch (error: Exception) {
      Timber.e(error, "Failed to restore live WebView from background overlay")
    } finally {
      backgroundOverlayManager = null
    }
  }

  private fun detachWebViewFromBackgroundOverlay() {
    if (!webViewAttachedToBackgroundOverlay) return
    try {
      backgroundOverlayManager?.removeView(webView)
    } catch (error: Exception) {
      Timber.e(error, "Failed to detach WebView from background overlay")
    } finally {
      webViewAttachedToBackgroundOverlay = false
      backgroundOverlayManager = null
    }
  }

  private fun restoreWebViewAfterAttachFailure() {
    val parent = originalWebViewParent ?: return
    if (webView.parent == null) {
      val params = originalWebViewLayoutParams
      val index = originalWebViewIndex.coerceIn(0, parent.childCount)
      if (params != null) {
        parent.addView(webView, index, params)
      } else {
        parent.addView(webView, index)
      }
    }
    webViewAttachedToBackgroundOverlay = false
    backgroundOverlayManager = null
  }

  override fun onBackPressed() {
    if (webView.canGoBack()) {
      webView.goBack()
    } else {
      super.onBackPressed()
    }
  }

  override fun onWebsiteLoaded(website: Website) {
    val title = website.safeLabel()
    setToolbarTitle(title)
    setToolbarSubtitle(website.url)
  }

  override fun onToolbarColorSet(websiteThemeColor: Int) {
    super.onToolbarColorSet(websiteThemeColor)
    setAppBarColor(websiteThemeColor)
  }

  override val layoutRes: Int get() = R.layout.activity_web_view

  private fun setupSwipeRefresh() {
    with(swipeRefreshLayout) {
      setOnRefreshListener {
        webView.reload()
      }
      setColorSchemeColors(
        ContextCompat.getColor(context, R.color.primary),
        ContextCompat.getColor(context, R.color.accent)
      )
    }
  }

  private fun setupToolbar() {
    setSupportActionBar(toolbar)
    supportActionBar?.apply {
      setDisplayHomeAsUpEnabled(true)
      setHomeAsUpIndicator(R.drawable.article_ic_close)
      title = website?.safeLabel() ?: intent.dataString
    }
    val toolbarColor = intent.getIntExtra(Constants.EXTRA_KEY_TOOLBAR_COLOR, 0)
    setAppBarColor(toolbarColor)
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun setupWebView(savedInstanceState: Bundle?) {
    try {
      webView.apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        webViewClient = object : ExternalAppInterceptingWebViewClient(externalAppLaunchHandler) {
          override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            url?.let {
              setToolbarSubtitle(url)
              loadWebsiteDetails(url)
            }
          }

          override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            hideLoading()
          }

          override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?
          ): Boolean {
            val didCrash = detail?.didCrash() == true
            Timber.e(
              "WebView renderer terminated; didCrash=%s, priority=%s, url=%s",
              didCrash,
              detail?.rendererPriorityAtExit(),
              view?.url
            )

            // WebView's renderer is a separate process. If this callback returns false Android
            // terminates the host app as well. Consume the renderer death here and recreate this
            // Activity with a fresh WebView instead of crashing Lynket.
            val deadView = view
            (deadView?.parent as? ViewGroup)?.removeView(deadView)
            deadView?.destroy()
            window.decorView.post {
              if (!isFinishing && !isDestroyed) {
                recreate()
              }
            }
            return true
          }
        }
        webChromeClient = object : WebChromeClient() {
          override fun onReceivedTitle(view: WebView?, title: String?) {
            super.onReceivedTitle(view, title)
            setToolbarTitle(title)
          }

          override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            setLoadingProgress(newProgress)
          }
        }
        // Ported from master d3996723: also enable DOM storage, else window.localStorage is null
        // and JS-async sites (e.g. jandan comments) fail with "加载失败" despite HTTP 200.
        WebViewConfigurator.configure(settings)
        val previousUrl = savedInstanceState?.getString(EXTRA_CURRENT_LOADING_URL)
        if (previousUrl == null) {
          loadUrl(intent.dataString!!)
        } else {
          loadUrl(previousUrl)
        }
      }

    } catch (e: InflateException) {
      Timber.e(e)
      Toast.makeText(this, R.string.web_view_not_found, Toast.LENGTH_LONG).show()
      finish()
    }
  }

  private fun setToolbarTitle(title: String?) {
    if (!TextUtils.isEmpty(title)) {
      toolbar.title = title
    }
  }

  private fun setToolbarSubtitle(subtitle: String?) {
    if (!TextUtils.isEmpty(subtitle) && toolbar.title != subtitle) {
      toolbar.subtitle = subtitle
    }
  }

  private fun setAppBarColor(themeColor: Int) {
    this.themeColor = themeColor
    foregroundColor = ColorUtil.getForegroundWhiteOrBlack(themeColor)
    fgColorStateList = ColorStateList.valueOf(foregroundColor)

    toolbar.apply {
      setBackgroundColor(themeColor)
      setTitleTextColor(foregroundColor)
      setSubtitleTextColor(foregroundColor)
      post {
        navigationIcon = navigationIcon?.applyColor(foregroundColor)
        overflowIcon = overflowIcon?.applyColor(foregroundColor)
      }
    }

    progressBar.apply {
      useIntrinsicPadding = false
      progressBackgroundTintList = ColorStateList.valueOf(themeColor)
      progressTintList = ColorStateList.valueOf(foregroundColor)
    }

    bottomNavigation.background = ColorDrawable(themeColor)
    bottomNavigation.itemIconTintList = ColorStateList.valueOf(foregroundColor)
    bottomNavigation.itemTextColor = ColorStateList.valueOf(foregroundColor)

    swipeRefreshLayout.setColorSchemeColors(themeColor, ColorUtil.getClosestAccentColor(themeColor))
    if (Utils.ANDROID_LOLLIPOP) {
      window.statusBarColor = ColorUtil.getDarkenedColorForStatusBar(themeColor)
    }
  }

  private fun setLoadingProgress(newProgress: Int) {
    progressBar.setAutoHideProgress(newProgress, fgColorStateList)
  }


  private fun showLoading() {
    swipeRefreshLayout.isRefreshing = true
  }


  private fun hideLoading() {
    swipeRefreshLayout.isRefreshing = false
  }
}
