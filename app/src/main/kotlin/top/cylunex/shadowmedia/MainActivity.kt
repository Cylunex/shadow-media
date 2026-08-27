package top.cylunex.shadowmedia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import top.cylunex.shadowmedia.ui.theme.ShadowMediaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ShadowMediaApplication).container
        setContent {
            ShadowMediaTheme {
                val viewModel: MainViewModel = viewModel(factory = MainViewModel.factory(container))
                ShadowMediaRoot(viewModel, container)
            }
        }
    }
}
