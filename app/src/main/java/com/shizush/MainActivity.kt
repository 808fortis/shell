package com.shizush

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.shizush.shizuku.ShizukuManager
import com.shizush.ui.MainScreen
import com.shizush.ui.theme.ShellTheme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private lateinit var shizukuManager: ShizukuManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        shizukuManager = ShizukuManager(applicationContext)

        Shizuku.addBinderReceivedListener(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        setContent {
            ShellTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(shizukuManager = shizukuManager)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeBinderReceivedListener(binderListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (_: Exception) {}
    }

    private val binderListener = Shizuku.OnBinderReceivedListener {
        shizukuManager.let { /* binder reconnected */ }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuManager.let { /* binder lost */ }
    }
}
