package com.jarvispoc.core

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Both operations are `suspend` on [Dispatchers.IO] deliberately: decoding and
 * re-encoding a full-resolution phone photo is hundreds of milliseconds of CPU,
 * and doing it inline in the picker callback would jank or ANR the UI thread.
 */
object Photos {

    /**
     * Newest image in the device gallery, or null if there is none.
     *
     * Unlike the photo picker this needs READ_MEDIA_IMAGES — "pick the latest
     * one for me" is only possible with access to the library as a whole. That
     * is a strictly broader grant than the picker's per-photo one, and it is
     * the price of the hands-free voice path.
     */
    suspend fun mostRecent(context: Context): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val projection = arrayOf(MediaStore.Images.Media._ID)
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    AgentLog.warn("no images found in the gallery")
                    return@use null
                }
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                ContentUris.withAppendedId(collection, id)
            }
        }.onFailure {
            AgentLog.error("could not read the gallery: ${it.message}")
        }.getOrNull()
    }

    /**
     * Longest edge we ever hold in memory.
     *
     * A modern phone photo decoded at full resolution is ~48 MB as ARGB_8888,
     * and it would be retained in Compose state for the lifetime of the screen
     * — in the same process as a multi-GB language model. That is a real OOM
     * risk for no benefit: the vision encoder is fed 768 px and Instagram
     * resamples feed images to ~1080 px anyway.
     */
    private const val MAX_EDGE_PX = 1_536

    /**
     * Decodes to a *software* bitmap, downsampled during decode.
     *
     * Two constraints drive this. ImageDecoder defaults to HARDWARE config on
     * API 28+, whose pixels MediaPipe cannot read. And `setTargetSize` inside
     * `onHeaderDecoded` means the full-resolution bitmap is never allocated at
     * all, rather than allocated and then shrunk.
     */
    suspend fun load(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                    decoder.setMutableRequired(false)
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > MAX_EDGE_PX) {
                        val ratio = MAX_EDGE_PX.toFloat() / longest
                        decoder.setTargetSize(
                            (info.size.width * ratio).toInt().coerceAtLeast(1),
                            (info.size.height * ratio).toInt().coerceAtLeast(1),
                        )
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val full = MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                shrink(full)
            }
        }.onFailure {
            AgentLog.error("could not decode photo: ${it.message}")
        }.getOrNull()
    }

    /** Post-hoc shrink for the pre-API-28 path, which cannot size during decode. */
    private fun shrink(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_EDGE_PX) return source
        val ratio = MAX_EDGE_PX.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    /**
     * Re-encodes the photo into our own files dir and returns a FileProvider
     * URI for it.
     *
     * Necessary because the photo picker's content URI is granted to *us*; we
     * cannot re-grant someone else's URI onward to Instagram. A URI we own, we
     * can grant.
     *
     * Each call writes a uniquely-named file and sweeps older ones, so we never
     * overwrite a file Instagram may still have open from a previous share.
     */
    suspend fun stageForShare(context: Context, bitmap: Bitmap): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "shared").apply { mkdirs() }
            val file = File(dir, "post-${System.currentTimeMillis()}.jpg")

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            dir.listFiles()
                ?.filter { it.name != file.name }
                ?.forEach { runCatching { it.delete() } }

            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.onFailure {
            AgentLog.error("could not stage photo for sharing: ${it.message}")
        }.getOrNull()
    }
}
