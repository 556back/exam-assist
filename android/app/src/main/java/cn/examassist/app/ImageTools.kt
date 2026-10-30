package cn.examassist.app

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

object ImageTools {
    fun attachment(bitmap: Bitmap): Attachment {
        var resized = bitmap
        try {
            val scale = minOf(1f, 2560f / max(bitmap.width, bitmap.height))
            if (scale < 1f) resized = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            val output = ByteArrayOutputStream()
            if (!resized.compress(Bitmap.CompressFormat.JPEG, 90, output)) throw ApiException("截图无法读取，请选择清晰的系统截图重试。")
            if (output.size() > 5 * 1024 * 1024) {
                output.reset()
                if (!resized.compress(Bitmap.CompressFormat.JPEG, 75, output)) throw ApiException("截图无法读取，请选择清晰的系统截图重试。")
            }
            return Attachment(resized, output.toByteArray())
        } catch (_: OutOfMemoryError) {
            if (resized !== bitmap) resized.recycle()
            throw ApiException("截图过大，手机内存不足，请裁剪题目区域后重试。")
        } catch (e: Exception) {
            if (resized !== bitmap) resized.recycle()
            throw e
        }
    }

    fun isBlack(bitmap: Bitmap): Boolean {
        for (row in 1..64) for (column in 1..64) {
            val x = (bitmap.width * column / 65).coerceAtMost(bitmap.width - 1)
            val y = (bitmap.height * (0.05f + 0.9f * row / 65f)).toInt().coerceAtMost(bitmap.height - 1)
            val color = bitmap.getPixel(x, y)
            if (Color.red(color) + Color.green(color) + Color.blue(color) > 45) return false
        }
        return true
    }

    suspend fun recognize(bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        suspendCancellableCoroutine { continuation ->
            var owned: Bitmap? = null
            var recognizer: TextRecognizer? = null
            try {
                // ML Kit cannot cancel an active task; its private copy stays valid after caller cancellation.
                val input = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw ApiException("图片无法读取，请选择清晰的系统截图重试。")
                owned = input
                val client = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                recognizer = client
                if (!continuation.isActive) {
                    runCatching { client.close() }
                    input.recycle()
                    return@suspendCancellableCoroutine
                }
                client.process(InputImage.fromBitmap(input, 0)).addOnCompleteListener { task ->
                    try {
                        if (continuation.isActive) {
                            if (task.isSuccessful) {
                                val pieces = task.result.textBlocks.flatMap { it.lines }.map { line ->
                                    PageTextOrder.Piece(line.text, line.boundingBox?.let { PageBounds(it.left, it.top, it.right, it.bottom) })
                                }
                                continuation.resume(PageTextOrder.order(pieces).joinToString("\n") { it.text })
                            }
                            else continuation.resumeWithException(ApiException("文字识别失败，请让题目完整显示后重试。"))
                        }
                    } finally {
                        runCatching { client.close() }
                        input.recycle()
                    }
                }
            } catch (_: OutOfMemoryError) {
                runCatching { recognizer?.close() }
                owned?.recycle()
                if (continuation.isActive) continuation.resumeWithException(ApiException("截图过大，手机内存不足，请裁剪题目区域后重试。"))
            } catch (e: Exception) {
                runCatching { recognizer?.close() }
                owned?.recycle()
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    }
}
