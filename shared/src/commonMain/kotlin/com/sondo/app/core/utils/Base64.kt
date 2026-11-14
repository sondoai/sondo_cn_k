package com.sondo.app.core.utils

/** 纯 Kotlin Base64（标准字母表）。原生模块回传的二进制片段用它解码。 */
object Base64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE = IntArray(128) { -1 }.also { table ->
        for (i in ALPHABET.indices) table[ALPHABET[i].code] = i
        // URL-safe 变体
        table['-'.code] = 62
        table['_'.code] = 63
    }

    fun decode(text: String): ByteArray {
        val out = ByteArray(text.length * 3 / 4 + 3)
        var n = 0
        var buffer = 0
        var bits = 0
        for (ch in text) {
            val c = ch.code
            if (c >= 128) continue
            val v = DECODE[c]
            if (v < 0) continue // 跳过 '='、换行等
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[n++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(n)
    }

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val v = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[v shr 18]).append(ALPHABET[(v shr 12) and 63]).append(ALPHABET[(v shr 6) and 63]).append(ALPHABET[v and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val v = (bytes[i].toInt() and 0xFF) shl 16
                sb.append(ALPHABET[v shr 18]).append(ALPHABET[(v shr 12) and 63]).append("==")
            }
            2 -> {
                val v = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                sb.append(ALPHABET[v shr 18]).append(ALPHABET[(v shr 12) and 63]).append(ALPHABET[(v shr 6) and 63]).append('=')
            }
        }
        return sb.toString()
    }
}
