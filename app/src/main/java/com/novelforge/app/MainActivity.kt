package com.novelforge.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novelforge.app.presentation.navigation.NovelForgeApp
import com.novelforge.app.ui.theme.NovelForgeTheme
import com.novelforge.app.ui.theme.rememberWallpaper
import com.novelforge.app.ui.theme.wallpaperFile

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            val app = application as NovelForgeApplication
            val settings by app.appSettingsStore.settings.collectAsStateWithLifecycle(initialValue = null)
            val systemDark = isSystemInDarkTheme()
            val dark = when (settings?.themeMode) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            val wallpaperName = settings?.wallpaperFileName.orEmpty()
            val wallpaper = rememberWallpaper(
                if (wallpaperName.isBlank()) null else wallpaperFile(app)
            )
            NovelForgeTheme(
                darkTheme = dark,
                wallpaper = wallpaper,
                wallpaperDim = (settings?.wallpaperDim ?: 62) / 100f
            ) {
                NovelForgeApp(application = app)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST
        )
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 101
    }
}
