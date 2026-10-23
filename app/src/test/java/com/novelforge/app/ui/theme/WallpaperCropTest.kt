package com.novelforge.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WallpaperCropTest {
    @Test
    fun cropRectMatchesPhoneFrameRatio() {
        val frameWidth = 1080f
        val frameHeight = 2400f
        val crop = wallpaperCropRect(
            imageWidth = 4000,
            imageHeight = 3000,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            userScale = 1f,
            offsetX = 0f,
            offsetY = 0f
        )
        val frameRatio = frameWidth / frameHeight
        val cropRatio = crop.width.toFloat() / crop.height
        assertTrue(abs(frameRatio - cropRatio) < 0.02f)
        assertTrue(crop.x >= 0 && crop.y >= 0)
        assertTrue(crop.x + crop.width <= 4000)
        assertTrue(crop.y + crop.height <= 3000)
    }

    @Test
    fun panCannotExposeEmptyEdges() {
        val crop = wallpaperCropRect(
            imageWidth = 2000,
            imageHeight = 2000,
            frameWidth = 1080f,
            frameHeight = 2400f,
            userScale = 1f,
            offsetX = 10_000f,
            offsetY = -10_000f
        )
        assertEquals(0, crop.x)
        assertTrue(crop.y + crop.height <= 2000)
    }
}
