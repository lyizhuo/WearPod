package com.example.wearpod.presentation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.example.wearpod.presentation.theme.WearPodTheme
import com.example.wearpod.settings.AppLanguageManager

class MainActivity : ComponentActivity() {
    private var openPlayerRequestNonce by mutableLongStateOf(0L)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            WearPodTheme {
                WearPodApp(openPlayerRequestNonce = openPlayerRequestNonce)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_PLAYER || intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            openPlayerRequestNonce = System.currentTimeMillis()
        }
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "com.example.wearpod.action.OPEN_PLAYER"
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}
