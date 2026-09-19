package com.novelforge.app.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novelforge.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeShowsProjectEntryPoints() {
        composeRule.onNodeWithText("NovelForge").assertIsDisplayed()
        composeRule.onNodeWithText("新建项目").assertIsDisplayed()
        composeRule.onNodeWithText("模型设置").assertIsDisplayed()
    }
}
