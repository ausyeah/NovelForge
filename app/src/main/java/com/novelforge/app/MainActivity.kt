package com.novelforge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.novelforge.app.presentation.navigation.NovelForgeApp
import com.novelforge.app.ui.theme.NovelForgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NovelForgeTheme {
                NovelForgeApp(application = application as NovelForgeApplication)
            }
        }
    }
}
