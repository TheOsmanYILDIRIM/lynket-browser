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

package arun.com.chromer.bubbles.webheads

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.annotation.IntRange
import arun.com.chromer.R
import arun.com.chromer.shared.base.service.BaseService
import arun.com.chromer.util.ServiceManager
import arun.com.chromer.util.Utils
import timber.log.Timber

/**
 * Created by Arunkumar on 24-02-2017.
 */
abstract class OverlayService : BaseService() {
  private var keepAliveWakeLock: PowerManager.WakeLock? = null
  private var keepAliveWifiLock: WifiManager.WifiLock? = null
  private var keepAliveOverlay: View? = null
  private var windowManager: WindowManager? = null

  @IntRange(from = 1)
  abstract fun getNotificationId(): Int

  abstract fun getNotification(): Notification

  abstract override fun onBind(intent: Intent): IBinder?

  override fun onCreate() {
    super.onCreate()
    checkForOverlayPermission()
    startForeground(getNotificationId(), getNotification())
    if (Utils.isOverlayGranted(this)) {
      startKeepAliveProtection()
    }
  }

  override fun onDestroy() {
    stopKeepAliveProtection()
    super.onDestroy()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (!Utils.isOverlayGranted(this)) {
      stopSelf(startId)
      return START_NOT_STICKY
    }
    startKeepAliveProtection()
    updateNotification(getNotification())
    return START_STICKY
  }

  private fun startKeepAliveProtection() {
    acquireWakeLock()
    acquireWifiLock()
    acquireInvisibleOverlay()
    requestBatteryOptimizationExemption()
  }

  private fun stopKeepAliveProtection() {
    releaseInvisibleOverlay()
    keepAliveWifiLock?.let { lock ->
      if (lock.isHeld) lock.release()
    }
    keepAliveWifiLock = null
    keepAliveWakeLock?.let { lock ->
      if (lock.isHeld) lock.release()
    }
    keepAliveWakeLock = null
  }

  private fun acquireWakeLock() {
    if (keepAliveWakeLock?.isHeld == true) return
    try {
      val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
      keepAliveWakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "${packageName}:webheads-keepalive"
      ).apply {
        setReferenceCounted(false)
        acquire()
      }
      Timber.d("Web heads keep-alive wake lock acquired")
    } catch (error: Exception) {
      keepAliveWakeLock = null
      Timber.e(error, "Unable to acquire Web Heads wake lock")
    }
  }

  @Suppress("DEPRECATION")
  private fun acquireWifiLock() {
    if (keepAliveWifiLock?.isHeld == true) return
    try {
      val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      keepAliveWifiLock = wifiManager.createWifiLock(
        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
        "${packageName}:webheads-wifi"
      ).apply {
        setReferenceCounted(false)
        acquire()
      }
      Timber.d("Web heads keep-alive Wi-Fi lock acquired")
    } catch (error: Exception) {
      keepAliveWifiLock = null
      Timber.e(error, "Unable to acquire Web Heads Wi-Fi lock")
    }
  }

  private fun acquireInvisibleOverlay() {
    if (keepAliveOverlay != null || !Utils.isOverlayGranted(this)) return
    try {
      val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
      val overlay = View(this).apply {
        setBackgroundColor(Color.TRANSPARENT)
      }
      val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
      }
      val params = WindowManager.LayoutParams(
        1,
        1,
        layoutType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
      ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
      }
      manager.addView(overlay, params)
      windowManager = manager
      keepAliveOverlay = overlay
      Timber.d("Invisible 1x1 keep-alive overlay attached")
    } catch (error: Exception) {
      Timber.e(error, "Unable to attach invisible keep-alive overlay")
    }
  }

  private fun releaseInvisibleOverlay() {
    val overlay = keepAliveOverlay ?: return
    try {
      windowManager?.removeView(overlay)
    } catch (error: Exception) {
      Timber.e(error, "Unable to remove invisible keep-alive overlay")
    } finally {
      keepAliveOverlay = null
      windowManager = null
    }
  }

  private fun requestBatteryOptimizationExemption() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
    try {
      startActivity(
        Intent(
          Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
          Uri.parse("package:$packageName")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    } catch (error: Exception) {
      Timber.e(error, "Unable to request battery optimization exemption")
    }
  }

  protected fun stopService() {
    Timber.d("Stopping service.")
    ServiceManager.restartAppDetectionService(this) // Temp hack for Oreo.
    stopForeground(true)
    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(getNotificationId())
    stopSelf()
  }

  protected fun updateNotification(notification: Notification) {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(getNotificationId(), notification)
  }

  protected fun checkForOverlayPermission() {
    if (!Utils.isOverlayGranted(this)) {
      Toast.makeText(this, getString(R.string.web_head_permission_toast), LENGTH_LONG).show()
      val intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      startActivity(intent)
      Timber.d("Exited overlay service since overlay permission was revoked")
      stopSelf()
    }
  }
}
