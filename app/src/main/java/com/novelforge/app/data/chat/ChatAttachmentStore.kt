package com.novelforge.app.data.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.novelforge.app.infrastructure.llm.ChatAttachment
import com.novelforge.app.ui.theme.decodeSampledBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 一条消息上挂的附件，**只存文件路径**，不存字节。
 *
 * 为什么不用 base64 内联：DataStore 是 preferences，整份会话历史都躺在里面。
 * 一张 2 MB 的图编成 base64 是 2.8 MB，40 条消息就是 100 MB ——
 * DataStore 读的时候在主线程反序列化，直接 ANR。所以图落到文件里，
 * 历史里只留一个路径。顺带还得到一个好处：会话历史重新打开时图片还在。
 */
@kotlinx.serialization.Serializable
data class StoredAttachment(
    val path: String,
    val name: String,
    val mediaType: String
)

/**
 * 聊天附件的落盘与读取。
 *
 * 关键约束：请求每轮都会重发整段会话，所以图会被反复上传。
 * 一次多图对话轻易把请求体顶到几 MB，而 OpenAI 兼容网关的请求体上限普遍在
 * 4–10 MB，超了只给你一个没有信息量的 `invalid request`。
 * 所以历史里只保留**最近若干条**消息的附件，并在界面上明说省略了多少。
 */
class ChatAttachmentStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "chat-attachments").apply { if (!exists()) mkdirs() }

    /** 把用户挑的图片压到「够看清、不至于撑爆请求体」的尺寸再落盘。 */
    suspend fun saveImage(uri: Uri, displayName: String): Result<StoredAttachment> =
        withContext(Dispatchers.IO) {
            runCatching {
                val decoded = decodeSampledBitmap(context, uri)
                    ?: error("无法读取该图片，文件可能已损坏")
                val scaled = downscale(decoded, MAX_EDGE)
                if (scaled !== decoded) decoded.recycle()
                val bytes = compressJpeg(scaled, MAX_IMAGE_BYTES)
                scaled.recycle()
                val file = File(dir, "att-${UUID.randomUUID()}.jpg")
                // 先写临时文件再改名：被杀不会留下半张图被当有效附件读出去
                val temp = File(dir, file.name + ".tmp")
                temp.outputStream().use { it.write(bytes) }
                if (!temp.renameTo(file)) {
                    temp.delete()
                    error("封面文件写入失败")
                }
                StoredAttachment(
                    path = file.absolutePath,
                    name = displayName.ifBlank { "图片" },
                    mediaType = "image/jpeg"
                )
            }
        }

    /** 文本类文档：整段读成文字，不做任何转码。 */
    suspend fun saveDocument(uri: Uri, displayName: String): Result<StoredAttachment> =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                } ?: error("这个文件读不出来")
                if (text.isBlank()) error("这个文件是空的")
                if (text.length > MAX_DOCUMENT_CHARS) {
                    error(
                        "文件过长：共 ${text.length} 字，本次最多发送 ${MAX_DOCUMENT_CHARS} 字。" +
                            "请改发摘要，或拆分成更小的片段。"
                    )
                }
                // 文档也落盘：历史里内联 10 万字进 DataStore 一样会把主线程拖死
                val file = File(dir, "att-${UUID.randomUUID()}.txt")
                val temp = File(dir, file.name + ".tmp")
                temp.writeText(text, Charsets.UTF_8)
                if (!temp.renameTo(file)) {
                    temp.delete()
                    error("附件文件写入失败")
                }
                StoredAttachment(
                    path = file.absolutePath,
                    name = displayName.ifBlank { "文档" },
                    mediaType = "text/plain"
                )
            }
        }

    suspend fun toChatAttachment(stored: StoredAttachment): ChatAttachment? =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(stored.path)
                if (!file.exists()) return@runCatching null
                if (stored.mediaType.startsWith("image/")) {
                    ChatAttachment.Image(
                        mediaType = stored.mediaType,
                        name = stored.name,
                        bytes = file.readBytes()
                    )
                } else {
                    ChatAttachment.Document(
                        mediaType = stored.mediaType,
                        name = stored.name,
                        text = file.readText(Charsets.UTF_8)
                    )
                }
            }.getOrNull()
        }

    /** 文件是否还在。历史里留着一条指向已删文件的记录时，靠它跳过而不是崩。 */
    suspend fun exists(stored: StoredAttachment): Boolean =
        withContext(Dispatchers.IO) { File(stored.path).exists() }

    suspend fun delete(stored: StoredAttachment) = withContext(Dispatchers.IO) {
        runCatching { File(stored.path).delete() }
        Unit
    }

    /**
     * 删掉不再被任何会话引用的附件文件。
     * 会话被删、消息被 trim 掉（只留 40 条）之后，孤儿文件会一直堆着 ——
     * 一次多图对话就能塞进几十 MB。
     */
    suspend fun collectGarbage(referenced: Collection<String>) = withContext(Dispatchers.IO) {
        val keep = referenced.toSet()
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.absolutePath !in keep) file.delete()
            }
        }
        Unit
    }

    companion object {
        /** 长边上限。手机照片 4000px 起步，模型看得清 1568 就绰绰有余。 */
        const val MAX_EDGE = 1568
        const val MAX_IMAGE_BYTES = 1_400_000
        const val MAX_DOCUMENT_CHARS = 100_000
    }
}

/** 等比缩到长边不超过 maxEdge。只缩不放。 */
internal fun downscale(source: Bitmap, maxEdge: Int): Bitmap {
    val longest = maxOf(source.width, source.height)
    if (longest <= maxEdge) return source
    val scale = maxEdge.toFloat() / longest
    return Bitmap.createScaledBitmap(
        source,
        (source.width * scale).toInt().coerceAtLeast(1),
        (source.height * scale).toInt().coerceAtLeast(1),
        true
    )
}

/**
 * 压成 JPEG，质量从 88 往下调直到进得了体积上限。
 * 宁可掉画质也不要超限：超了是整个请求被网关拒掉，用户什么都得不到。
 */
internal fun compressJpeg(source: Bitmap, maxBytes: Int): ByteArray {
    for (quality in intArrayOf(88, 78, 66, 55, 45)) {
        val stream = java.io.ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        val bytes = stream.toByteArray()
        if (bytes.size <= maxBytes) return bytes
    }
    val stream = java.io.ByteArrayOutputStream()
    source.compress(Bitmap.CompressFormat.JPEG, 40, stream)
    return stream.toByteArray()
}
