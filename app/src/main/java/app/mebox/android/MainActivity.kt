package app.mebox.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.mebox.android.core.ui.MeBoxTheme
import app.mebox.android.feature.MainViewModel
import app.mebox.android.feature.MeBoxRoot

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory((application as MeBoxApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MeBoxTheme {
                MeBoxRoot(viewModel)
            }
        }
    }
}
