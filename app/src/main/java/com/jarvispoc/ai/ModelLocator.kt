package com.jarvispoc.ai

import android.content.Context
import java.io.File

/**
 * Finds the .litertlm weights on device.
 *
 * Google's samples push to /data/local/tmp/llm/, but SELinux on OEM ROMs
 * (Funtouch/OriginOS on the iQOO among them) often blocks an app from reading
 * that path even though `adb push` writes it happily. The app's own external
 * files dir always works and is still push-able, so it is preferred and
 * /data/local/tmp is kept only as a fallback.
 */
object ModelLocator {

    const val MODEL_FILE = "gemma-3n-E2B-it-int4.litertlm"

    fun preferredDir(context: Context): File =
        File(context.getExternalFilesDir(null), "llm")

    fun preferredPath(context: Context): String =
        File(preferredDir(context), MODEL_FILE).absolutePath

    fun candidates(context: Context): List<File> = listOf(
        File(preferredDir(context), MODEL_FILE),
        File("/data/local/tmp/llm/$MODEL_FILE"),
    )

    fun resolve(context: Context): File? =
        candidates(context).firstOrNull { runCatching { it.isFile && it.canRead() }.getOrDefault(false) }

    /** One-line status for the control panel. */
    fun describe(context: Context): String {
        val found = resolve(context)
        return if (found != null) {
            "model: ${found.name} (${found.length() / (1024 * 1024)} MB) at ${found.parent}"
        } else {
            "model MISSING — adb push it to ${preferredPath(context)}"
        }
    }
}
