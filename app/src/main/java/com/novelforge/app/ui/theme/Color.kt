package com.novelforge.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── 纸质风格色板：暖白纸面 + 墨色文字 + 朱砂主色 ──
val Ink = Color(0xFF2B2620)
val Paper = Color(0xFFF7F4EC)
val PaperCard = Color(0xFFFFFDF6)
// 描边：原来 #E0D8C6 在纸面上只有 1.42:1，卡片边缘几乎看不见，
// 加上浅色 background 与 surface 只差 1.05:1，边框就成了唯一能认出卡片区域的信息，
// 按 WCAG 1.4.11（非文字 3:1）必须压到 3:1 以上。色相不变，只是把暖灰加深。
val PaperLine = Color(0xFF8C8679)
// 次要文字：全站大量说明性正文都用它，原来 #8A8272 在 surface 上只有 3.80:1、
// 在更暗的 surfaceVariant 上只剩 3.20:1，达不到正文 4.5:1。
// 加深到 4 种浅色底（background/surface/surfaceVariant/primaryContainer）全部 ≥4.5:1。
val PaperMuted = Color(0xFF6D6656)
val Accent = Color(0xFFB14A38)
val AccentDeep = Color(0xFF8F3A2B)
val AccentSoft = Color(0xFFF6E3DC)
val SuccessGreen = Color(0xFF5C7A5A)
val WarmOrange = Color(0xFFC97A2D)
