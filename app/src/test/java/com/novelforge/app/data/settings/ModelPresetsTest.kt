package com.novelforge.app.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelPresetsTest {
    @Test
    fun sameProviderDifferentApiGetsNumberedLabel() {
        val first = upsertModelPreset(
            existing = emptyList(),
            editingId = null,
            providerName = "DeepSeek",
            baseUrl = "https://a.example",
            model = "a",
            disableThinking = true,
            apiKey = "key-a",
            newId = { "1" }
        )
        val second = upsertModelPreset(
            existing = first,
            editingId = null,
            providerName = "DeepSeek",
            baseUrl = "https://b.example",
            model = "b",
            disableThinking = true,
            apiKey = "key-b",
            newId = { "2" }
        )
        assertEquals(listOf("DeepSeek", "DeepSeek（2）"), second.map { it.label })
    }

    @Test
    fun sameApiUpdatesInsteadOfDuplicating() {
        val first = upsertModelPreset(
            existing = emptyList(),
            editingId = null,
            providerName = "DeepSeek",
            baseUrl = "https://a.example/",
            model = "a",
            disableThinking = true,
            apiKey = "key-a",
            newId = { "1" }
        )
        val again = upsertModelPreset(
            existing = first,
            editingId = null,
            providerName = "DeepSeek",
            baseUrl = "https://a.example",
            model = "a",
            disableThinking = false,
            apiKey = "key-a",
            newId = { "should-not-use" }
        )
        assertEquals(1, again.size)
        assertEquals("1", again.single().id)
        assertEquals(false, again.single().disableThinking)
    }

    @Test
    fun editingKeepsItsSlotWhenProviderNameStays() {
        val existing = listOf(
            ModelPreset("1", "DeepSeek", "DeepSeek", "https://a.example", "a", true, "k1"),
            ModelPreset("2", "DeepSeek（2）", "DeepSeek", "https://b.example", "b", true, "k2")
        )
        val saved = upsertModelPreset(
            existing = existing,
            editingId = "2",
            providerName = "DeepSeek",
            baseUrl = "https://b.example",
            model = "b-new",
            disableThinking = true,
            apiKey = "k2-new",
            newId = { "3" }
        )
        assertEquals(listOf("1", "2"), saved.map { it.id })
        assertEquals("DeepSeek（2）", saved.last().label)
        assertEquals("b-new", saved.last().model)
    }
}
