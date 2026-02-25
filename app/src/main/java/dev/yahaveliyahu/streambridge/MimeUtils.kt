package dev.yahaveliyahu.streambridge

import android.webkit.MimeTypeMap

object MimeUtils {
    fun getMimeTypeFromName(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }
}
