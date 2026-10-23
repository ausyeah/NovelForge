package com.novelforge.app.data.cover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面裁剪几何的回归测试。
 *
 * 书架封面是竖版（宽:高 = 0.72）。用户从相册里挑的图可能是横的，
 * 不裁就意味着书封被压扁 —— 而「压扁的书封」比「没封面」更难看。
 *
 * 这里测的是 [portraitCropPlan]，也就是 `centerCropToPortrait` 真正使用的那份
 * 计算（不是它的副本）。位图裁剪本身要 android.graphics，得上设备手测；
 * 但「裁哪一块、缩到多大」这些算术如果错了，症状就是封面变形或被放大糊掉，
 * 这里能挡住。
 */
class BookCoverGeometryTest {
    @Test
    fun wideSourceIsCroppedHorizontally() {
        val plan = portraitCropPlan(sourceWidth = 2000, sourceHeight = 1000, maxSide = 900)
        assertEquals("太高时保留全部高度", 1000, plan.cropHeight)
        // 1000 * 0.72 = 720
        assertEquals(720, plan.cropWidth)
        assertEquals("从左右居中裁", (2000 - 720) / 2, plan.left)
        assertEquals(0, plan.top)
    }

    @Test
    fun tallSourceIsCroppedVertically() {
        val plan = portraitCropPlan(sourceWidth = 600, sourceHeight = 2000, maxSide = 900)
        assertEquals("太宽时保留全部宽度", 600, plan.cropWidth)
        assertEquals(600 / 0.72f, plan.cropHeight.toFloat(), 1f)
        assertEquals(0, plan.left)
        assertTrue("从上下居中裁", plan.top > 0)
    }

    @Test
    fun exactPortraitSourceIsNotCropped() {
        val plan = portraitCropPlan(sourceWidth = 720, sourceHeight = 1000, maxSide = 900)
        assertEquals(720, plan.cropWidth)
        assertEquals(1000, plan.cropHeight)
        assertEquals(0, plan.left)
        assertEquals(0, plan.top)
    }

    @Test
    fun scaleOnlyShrinksNeverEnlarges() {
        // 小图不该被放大：放大只会糊，还白占空间
        val small = portraitCropPlan(sourceWidth = 180, sourceHeight = 250, maxSide = 900)
        assertEquals(180, small.outWidth)
        assertEquals(250, small.outHeight)

        val large = portraitCropPlan(sourceWidth = 2000, sourceHeight = 2800, maxSide = 900)
        assertTrue("大图要缩到上限内", maxOf(large.outWidth, large.outHeight) <= 900)
    }

    @Test
    fun outputStaysWithinBoundsForDegenerateInput() {
        // 1x1 的图不该把 outWidth 算成 0，否则 createScaledBitmap 直接抛异常
        val plan = portraitCropPlan(sourceWidth = 1, sourceHeight = 1, maxSide = 900)
        assertTrue(plan.outWidth >= 1)
        assertTrue(plan.outHeight >= 1)
        assertTrue(plan.outWidth <= 1)
        assertTrue(plan.outHeight <= 1)
    }

    @Test
    fun cropRectStaysInsideTheSource() {
        val plan = portraitCropPlan(sourceWidth = 3000, sourceHeight = 1200, maxSide = 900)
        assertTrue(plan.left >= 0)
        assertTrue(plan.top >= 0)
        assertTrue(plan.left + plan.cropWidth <= 3000)
        assertTrue(plan.top + plan.cropHeight <= 1200)
    }
}
