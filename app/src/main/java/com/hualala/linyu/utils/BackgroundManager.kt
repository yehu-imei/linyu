package com.hualala.linyu.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自定义背景图片的读写与缓存。
 *
 * 支持两套**互相独立**的背景：
 * - [SCOPE_HOME]   ：首页 / 账单 / 我的
 * - [SCOPE_SHOWER] ：使用页（洗澡中）
 *
 * 性能要点：
 * - 存图时按最大边 [MAX_DIM] 缩放，避免 4000×3000 大图占内存
 * - 读图带缓存，只在文件变化时重新解码，**不会每帧解码**
 */
object BackgroundManager {
    const val SCOPE_HOME = "home"
    const val SCOPE_SHOWER = "shower"

    private const val MAX_DIM = 2560 // 背景最大边（px）

    // 每套背景各自缓存
    private val cache = HashMap<String, ImageBitmap?>()
    private val cacheStamp = HashMap<String, Long>()
    private var pluginDataVersion = 0

    fun imageFile(context: Context, scope: String): File =
        File(context.filesDir, "background_$scope.jpg")

    fun hasImage(context: Context, scope: String): Boolean = imageFile(context, scope).exists()

    /** 从相册 Uri 复制到私有目录（自动缩放） */
    suspend fun saveFromUri(context: Context, uri: Uri, scope: String): Boolean {
        // 相册解码在 IO 线程上，卸载后不得把正在处理的旧图写回来。
        val version = synchronized(this) { pluginDataVersion }
        return withContext(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext false
                val bmp = decodeScaled(bytes, MAX_DIM) ?: return@withContext false
                try {
                    synchronized(this@BackgroundManager) {
                        if (version != pluginDataVersion) return@withContext false
                        imageFile(context, scope).outputStream().use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        }.also { cache.remove(scope) }
                    }
                } finally {
                    bmp.recycle()
                }
            } catch (e: Exception) {
                AppLogger.e("保存自定义背景失败($scope)", e)
                false
            }
        }
    }

    /** 加载背景图（带缓存，避免每帧解码） */
    @Synchronized
    fun loadBitmap(context: Context, scope: String): ImageBitmap? {
        val f = imageFile(context, scope)
        if (!f.exists()) {
            cache[scope] = null
            return null
        }
        val stamp = f.lastModified()
        cache[scope]?.let { if (cacheStamp[scope] == stamp) return it }
        return try {
            BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()?.also {
                cache[scope] = it
                cacheStamp[scope] = stamp
            }
        } catch (e: Exception) {
            AppLogger.e("加载自定义背景失败($scope)", e)
            null
        }
    }

    @Synchronized
    fun clear(context: Context, scope: String): Boolean {
        val removed = runCatching {
            val image = imageFile(context, scope)
            !image.exists() || image.delete()
        }.getOrDefault(false)
        if (removed) {
            cache.remove(scope)
            cacheStamp.remove(scope)
        }
        return removed
    }

    @Synchronized
    internal fun clearPluginData(context: Context): Boolean {
        pluginDataVersion++
        val homeRemoved = clear(context, SCOPE_HOME)
        val showerRemoved = clear(context, SCOPE_SHOWER)
        if (!homeRemoved || !showerRemoved) return false
        PrefsHelper.clearBackgroundOptions()
        BackgroundState.refresh()
        BackgroundState.notifyImageChanged()
        return true
    }

    /**
     * 从当前背景图提取「UI 可用的主题色」。
     *
     * 做法：缩到 48×48 → 颜色量化统计 → 过滤过暗/过亮/过灰 → 取占比最高的 →
     * 再把饱和度/亮度夹到适合 UI 的范围（不直接使用原图颜色）。
     * 失败返回 null。
     */
    fun extractThemeColor(context: Context, scope: String): Int? {
        val f = imageFile(context, scope)
        if (!f.exists()) return null
        return try {
            val src = BitmapFactory.decodeFile(f.absolutePath) ?: return null
            val small = Bitmap.createScaledBitmap(src, 48, 48, true)
            val pixels = IntArray(48 * 48)
            small.getPixels(pixels, 0, 48, 0, 0, 48, 48)
            if (small !== src) small.recycle()
            src.recycle()

            // 每通道按 32 一档量化后统计
            val counts = HashMap<Int, Int>()
            for (p in pixels) {
                val q = android.graphics.Color.rgb(
                    (android.graphics.Color.red(p) / 32) * 32,
                    (android.graphics.Color.green(p) / 32) * 32,
                    (android.graphics.Color.blue(p) / 32) * 32
                )
                counts[q] = (counts[q] ?: 0) + 1
            }

            val hsv = FloatArray(3)
            val best = counts.entries
                .filter { (c, _) ->
                    android.graphics.Color.colorToHSV(c, hsv)
                    hsv[1] > 0.15f && hsv[2] > 0.20f && hsv[2] < 0.95f
                }
                .maxByOrNull { it.value }?.key ?: return null

            // 调整到适合 UI 的饱和度 / 亮度
            android.graphics.Color.colorToHSV(best, hsv)
            hsv[1] = hsv[1].coerceIn(0.35f, 0.75f)
            hsv[2] = hsv[2].coerceIn(0.45f, 0.85f)
            android.graphics.Color.HSVToColor(hsv)
        } catch (e: Exception) {
            AppLogger.e("提取主题色失败", e)
            null
        }
    }

    private fun decodeScaled(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
