/*
 * Lynket
 */
package arun.com.chromer.browsing.floating

import android.app.Application
import android.content.Context
import arun.com.chromer.bubbles.FloatingBubble
import arun.com.chromer.data.website.model.Website
import arun.com.chromer.util.Utils
import dev.arunkumar.android.rxschedulers.SchedulerProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FloatingBrowserFloatingBubble
@Inject
constructor(
  private val application: Application,
  private val schedulerProvider: SchedulerProvider
) : FloatingBubble {
  override fun openBubble(
    website: Website,
    fromMinimize: Boolean,
    fromAmp: Boolean,
    incognito: Boolean,
    context: Context?,
    color: Int
  ) {
    val ctx = context ?: application
    if (Utils.isOverlayGranted(ctx)) {
      FloatingBrowserService.open(ctx, website.preferredUrl())
    } else {
      schedulerProvider.ui.scheduleDirect { Utils.openDrawOverlaySettings(ctx) }
    }
  }
}
