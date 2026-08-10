package com.oppovisual.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.oppovisual.app.background.BackgroundGestureControl
import com.oppovisual.app.ui.OppoVisualApp
import com.oppovisual.app.ui.theme.OppoVisualTheme

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        if (BackgroundGestureControl.state.value.active) {
            BackgroundGestureControl.stop(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            OppoVisualTheme {
                OppoVisualApp()
            }
        }
    }
}
