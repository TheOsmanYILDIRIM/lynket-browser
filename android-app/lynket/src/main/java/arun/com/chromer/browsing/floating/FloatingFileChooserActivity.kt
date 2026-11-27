package arun.com.chromer.browsing.floating

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient

/**
 * Thin Activity bridge for WebView file inputs hosted by FloatingBrowserService.
 * A Service/overlay cannot receive activity results directly, so this activity
 * launches Android's normal document/photo picker and returns the selected URIs
 * to the live WebView callback in the same process.
 */
class FloatingFileChooserActivity : Activity() {

  companion object {
    const val EXTRA_REQUEST_ID = "floating_file_chooser_request_id"
    const val EXTRA_CHOOSER_INTENT = "floating_file_chooser_intent"
    private const val REQUEST_PICK_FILE = 5401
  }

  private var requestId: Long = -1L

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    requestId = intent.getLongExtra(EXTRA_REQUEST_ID, -1L)
    if (requestId < 0L) {
      finish()
      return
    }

    if (savedInstanceState == null) {
      @Suppress("DEPRECATION")
      val chooserIntent = intent.getParcelableExtra<Intent>(EXTRA_CHOOSER_INTENT)
      if (chooserIntent == null) {
        FloatingBrowserService.cancelFileChooser(requestId)
        finish()
        return
      }
      try {
        startActivityForResult(chooserIntent, REQUEST_PICK_FILE)
      } catch (error: Exception) {
        FloatingBrowserService.cancelFileChooser(requestId)
        finish()
      }
    }
  }

  @Deprecated("Deprecated in Android API; retained for this project's min/compile SDK.")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode != REQUEST_PICK_FILE) return

    val uris: Array<Uri>? = if (resultCode == RESULT_OK) {
      WebChromeClient.FileChooserParams.parseResult(resultCode, data)
    } else {
      null
    }
    FloatingBrowserService.deliverFileChooserResult(requestId, uris)
    finish()
  }

  override fun onDestroy() {
    if (isFinishing && requestId >= 0L) {
      // No-op if a result already consumed the callback.
      FloatingBrowserService.cancelFileChooser(requestId)
    }
    super.onDestroy()
  }
}
