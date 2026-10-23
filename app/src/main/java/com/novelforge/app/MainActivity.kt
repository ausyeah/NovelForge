package com.novelforge.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novelforge.app.data.settings.AppSettings
import com.novelforge.app.presentation.navigation.NovelForgeApp
import com.novelforge.app.ui.theme.NovelForgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            val app = application as NovelForgeApplication
            // 不能用 collectAsStateWithLifecycle(initialValue = null)：
            // 它在 STOPPED 以下会退订，打开相册 / SAF 建文档 / 导入选择器时 Activity 一定
            // 走到 STOPPED，回来重新订阅会先吐 initialValue=null，
            // 于是 settings==null → dark=isSystemInDarkTheme()，
            // 应用主题和系统主题不一致时整屏闪一下（每次导出备份、每次换壁纸都闪）。
            // 这里只保留「最后读到的非空设置」，流短暂断开也不会退回系统色。
            var settings by remember { mutableStateOf<AppSettings?>(null) }
            LaunchedEffect(app) {
                app.appSettingsStore.settings.collect { settings = it }
            }
            val systemDark = isSystemInDarkTheme()
            val dark = when (settings?.themeMode) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            val wallpaper by app.wallpaperStore.current.collectAsStateWithLifecycle()
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
