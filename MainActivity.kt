package com.darknes.tor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private lateinit var state: BrowserState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val prefs = Prefs(this)
        val auth = Auth(this)
        val ai = AiStore(this)
        state = BrowserState(this, prefs)
        state.setupExtensions()
        state.applyFont()
        state.newTab(intent?.data?.toString() ?: "")

        setContent {
            DarknesTheme {
                LaunchedEffect(state.fullscreen) {
                    val c = WindowCompat.getInsetsController(window, window.decorView)
                    c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (state.fullscreen) c.hide(WindowInsetsCompat.Type.systemBars())
                    else c.show(WindowInsetsCompat.Type.systemBars())
                }
                BrowserApp(state, prefs, auth, ai)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let { state.newTab(it) }
    }
}
