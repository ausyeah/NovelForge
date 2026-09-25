package com.novelforge.app.data.chat

import com.novelforge.app.infrastructure.llm.ChatAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附件的持久化契约。
 *
 * 这里钉住的都是「将来很容易被改坏」的规则，不是实现细节：
 * - 附件只存路径。DataStore 是在主线程反序列化的，一旦有人改成内联 base64，
 *   几张图就能把主线程拖死，而这种改动在编译和单测里都看不出来。
 * - 体积上限按「base64 会膨胀 1/3」来算，不是拍脑袋。
 */
class ChatAttachmentStoreContractTest {

    @Test
    fun storedAttachmentStaysTiny() {
        // 一条路径 + 一个文件名 + 一个 mediaType。历史里 40 条消息 × 5 个附件
        // 也就是几百字节。改成内联字节之后这个测试不会失败，但 DataStore 会炸。
        val stored = StoredAttachment(
            path = "/data/user/0/com.novelforge.app/files/chat-attachments/att-123.jpg",
            name = "封面草图.jpg",
            mediaType = "image/jpeg"
        )
        assertTrue(
            "历史里的一条附件记录必须很小，实际 ${stored.toString().length} 字符",
            stored.toString().length < 200
        )
    }

    @Test
    fun imageLimitLeavesRoomForBase64Inflation() {
        // base64 是 4/3 膨胀。1.4 MB 的图编完是 1.9 MB，
        // 加上文字和 prompt 之后仍在网关常见的 4 MB 预算内。
        val limit = ChatAttachmentStore.MAX_IMAGE_BYTES
        val inflated = (limit + 2) / 3 * 4
        assertTrue("base64 后必须小于 2 MB，实际 ${inflated / 1024f / 1024f} MB", inflated < 2_100_000)
    }

    @Test
    fun maxEdgeIsLargeEnoughToReadTextInAScreenshot() {
        // 太小模型看不清截图里的字，太大只是白占请求体。
        // 1568 是主流视觉模型的实际输入上限附近。
        assertTrue(ChatAttachmentStore.MAX_EDGE >= 1024)
        assertTrue(ChatAttachmentStore.MAX_EDGE <= 2048)
    }

    @Test
    fun documentLimitIsBoundedBecauseItGoesInline() {
        // 文本附件是整段内联进请求的，不是落盘后再读 ——
        // 所以它直接决定请求体大小，必须有硬上限。
        assertTrue(ChatAttachmentStore.MAX_DOCUMENT_CHARS > 0)
        // 10 万字约等于 30 万 token，超过任何网关的窗口
        assertTrue(
            "文档上限不该大到撑爆请求体",
            ChatAttachmentStore.MAX_DOCUMENT_CHARS <= 100_000
        )
    }

    @Test
    fun chatAttachmentRejectsAnImageMediaTypeOnADocument() {
        // 把 pdf 当图片发出去，到网关才报错，而网关给的是
        // 「invalid content」这种没法自查的 400。构造时就该拦住。
        val error = runCatching {
            ChatAttachment.Image(
                mediaType = "application/pdf",
                name = "稿子.pdf",
                bytes = byteArrayOf(1, 2, 3)
            )
        }.exceptionOrNull()
        assertNotNull("非 image/* 的图片附件必须在构造时就被拒绝", error)
    }

    @Test
    fun chatAttachmentRejectsEmptyPayloads() {
        assertNotNull(
            runCatching {
                ChatAttachment.Image(mediaType = "image/png", name = "a.png", bytes = ByteArray(0))
            }.exceptionOrNull()
        )
        assertNotNull(
            runCatching {
                ChatAttachment.Document(mediaType = "text/plain", name = "a.txt", text = "")
            }.exceptionOrNull()
        )
        assertNotNull(
            runCatching {
                ChatAttachment.Image(mediaType = "image/png", name = "  ", bytes = byteArrayOf(1))
            }.exceptionOrNull()
        )
    }

    @Test
    fun wireMediaTypeStripsParametersAndLowercases() {
        // data URI 里只允许裸类型。带 `; charset=utf-8` 会让部分网关解析失败。
        val attachment = ChatAttachment.Document(
            mediaType = "Text/Plain; charset=UTF-8",
            name = "a.txt",
            text = "x"
        )
        assertEquals("text/plain", attachment.wireMediaType)
    }

    @Test
    fun imageEqualityComparesContentNotReference() {
        // ByteArray 的 equals 是引用比较。data class 默认生成的那版会把
        // 「两张内容相同的图」判成不相等，去重和测试都会踩坑。
        val a = ChatAttachment.Image("image/png", "a.png", byteArrayOf(1, 2, 3))
        val b = ChatAttachment.Image("image/png", "a.png", byteArrayOf(1, 2, 3))
        val c = ChatAttachment.Image("image/png", "b.png", byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
    }
}
