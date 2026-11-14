package com.sondo.app.core.utils

import kotlin.random.Random

object Uuid {
    private const val HEX = "0123456789abcdef"

    /** RFC 4122 v4 */
    fun v4(): String {
        val b = Random.nextBytes(16)
        b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
        val sb = StringBuilder(36)
        for (i in 0 until 16) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-')
            val v = b[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }
}

/** RFC 3986 百分号编码（UTF-8）。 */
fun urlEncode(s: String): String {
    val sb = StringBuilder(s.length + 8)
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (c < 128 && (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~')) {
            sb.append(ch)
        } else {
            sb.append('%').append("0123456789ABCDEF"[c ushr 4]).append("0123456789ABCDEF"[c and 0x0F])
        }
    }
    return sb.toString()
}

fun urlDecode(s: String): String {
    val out = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        if (ch == '%' && i + 2 < s.length + 0 && i + 2 <= s.length - 1 + 0) {
            val v = s.substring(i + 1, i + 3).toIntOrNull(16)
            if (v != null) {
                out.add(v.toByte()); i += 3; continue
            }
        }
        if (ch == '+') {
            out.add(' '.code.toByte()); i++; continue
        }
        ch.toString().encodeToByteArray().forEach { out.add(it) }
        i++
    }
    return out.toByteArray().decodeToString()
}

/** 解析 URL 查询串为 Map（重复 key 取最后一个）。 */
fun parseQuery(url: String): Map<String, String> {
    val q = url.substringAfter('?', "").substringBefore('#')
    if (q.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (part in q.split('&')) {
        if (part.isEmpty()) continue
        val k = part.substringBefore('=')
        val v = part.substringAfter('=', "")
        out[urlDecode(k)] = urlDecode(v)
    }
    return out
}
