package com.novelforge.app.presentation.common

/** 章节展示名：全书第 1 个推进段是引子，之后按正文第 1、2、… 章计 */
fun chapterLabel(orderIndex: Int): String =
    if (orderIndex <= 0) "引子" else "第 $orderIndex 章"

private val TITLE_NUMBERING_PREFIX = Regex(
    "^(引子|楔子|序章|序幕|第\\s*[0-9〇零一二两三四五六七八九十百千]+\\s*[章回节]|chapter\\s*\\d+)\\s*[:：·•\\-—～~\\s]*",
    RegexOption.IGNORE_CASE
)

/**
 * 去掉模型自己写进标题里的编号前缀（“引子：”“第一章：”等），
 * 避免与展示层拼接的“引子 · ”“第 1 章 · ”重复。
 */
fun cleanChapterTitle(title: String): String =
    title.replace(TITLE_NUMBERING_PREFIX, "").trim()
        .ifEmpty { title.trim() }
