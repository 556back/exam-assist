package cn.examassist.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** PDF and image OCR is an import input method, independent of answer-mode settings. */
internal object BankDocumentReader {
    suspend fun extract(context: Context, bytes: ByteArray, filename: String, progress: (String) -> Unit, warning: (String) -> Unit = {}): String? {
        return when (filename.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "pdf" -> pdf(context, bytes, progress, warning)
            "png", "jpg", "jpeg", "webp" -> {
                progress("正在识别题库图片")
                val bitmap = withContext(Dispatchers.Default) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ApiException("图片文件无法读取，请重新导出。")
                    val options = BitmapFactory.Options().apply {
                        while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 4096) inSampleSize *= 2
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        ?: throw ApiException("图片文件无法读取，请重新导出。")
                }
                recognize(bitmap).ifBlank { throw ApiException("图片没有识别到题目文字，请使用更清晰的图片。") }
            }
            else -> null
        }
    }

    private suspend fun pdf(context: Context, bytes: ByteArray, progress: (String) -> Unit, warning: (String) -> Unit): String {
        val file = withContext(Dispatchers.IO) {
            File.createTempFile("bank-import-", ".pdf", context.cacheDir).also { it.writeBytes(bytes) }
        }
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val renderer = try { PdfRenderer(descriptor) } catch (_: Exception) {
                    throw ApiException("PDF 无法读取，请检查文件是否损坏或受密码保护。")
                }
                renderer.use {
                    if (it.pageCount !in 1..50) throw ApiException("PDF 最多支持 50 页，请拆分后导入。")
                    val text = StringBuilder()
                    for (index in 0 until it.pageCount) {
                        coroutineContext.ensureActive()
                        progress("正在识别 PDF 第 ${index + 1}/${it.pageCount} 页")
                        val bitmap = withContext(Dispatchers.Default) {
                            it.openPage(index).use { page ->
                                val scale = minOf(2f, 2400f / maxOf(page.width, page.height))
                                Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                                    (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { image ->
                                    image.eraseColor(Color.WHITE)
                                    page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            }
                        }
                        val pageText = recognize(bitmap)
                        if (pageText.isBlank()) {
                            warning("PDF 第 ${index + 1} 页未识别到文字，已跳过，请核对是否包含题目。")
                            continue
                        }
                        text.append(pageText).append('\n')
                        if (text.length > 1_000_000) throw ApiException("识别文字过多，请拆分题库后导入。")
                    }
                    return text.toString().ifBlank { throw ApiException("PDF 没有识别到文字，请使用更清晰的文件。") }
                }
            }
        } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { file.delete() } }
    }

    private suspend fun recognize(bitmap: Bitmap): String {
        // ML Kit keeps its bitmap until completion; cancellation leaves it for normal GC.
        var finished = false
        try { return ImageTools.recognize(bitmap).also { finished = true } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { finished = true; throw e }
        finally { if (finished) bitmap.recycle() }
    }
}
