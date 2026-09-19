package com.novelforge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novelforge.app.presentation.navigation.NovelForgeApp
import com.novelforge.app.ui.theme.NovelForgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val app = application as NovelForgeApplication
            val settings by app.appSettingsStore.settings.collectAsStateWithLifecycle(initialValue = null)
            val systemDark = isSystemInDarkTheme()
            val dark = when (settings?.themeMode) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            NovelForgeTheme(darkTheme = dark) {
                NovelForgeApp(application = app)
            }
        }
    }
}
