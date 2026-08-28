package top.cylunex.shadowmedia

import android.os.Bundle
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import top.cylunex.shadowmedia.ui.theme.ShadowMediaTheme

class MainActivity : ComponentActivity() {
    private var playbackActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ShadowMediaApplication).container
        container.handoffInbox.offer(intent?.data)
        setContent {
            ShadowMediaTheme {
                val viewModel: MainViewModel = viewModel(factory = MainViewModel.factory(container))
                ShadowMediaRoot(viewModel, container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (application as ShadowMediaApplication).container.handoffInbox.offer(intent.data)
    }

    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val isTv = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION
        if (
            playbackActive && !isTv && !isInPictureInPictureMode &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        }
    }
}
