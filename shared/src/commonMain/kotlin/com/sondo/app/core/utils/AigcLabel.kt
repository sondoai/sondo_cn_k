package com.sondo.app.core.utils

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * GB 45438—2025 附录 E 的隐式标识。各字段值都必须是字符串。
 *
 * 参考工程里本类没有任何调用方（客户端既不写也不校验隐式标识，文件按下载结果原样交付）。
 * 这里保留字段定义、JSON 读写与明文扫描；PNG 文本块读写（embedPng / readPng）依赖图片编解码库，
 * 属于未接入的死代码，未移植。
 */
class AigcLabel(
    val contentProducer: String,
    val produceId: String,
    val label: String = "1",
    val reservedCode1: String = "",
    val reservedCode2: String = "",
    contentPropagator: String? = null,
    propagateId: String? = null,
) {
    /** 首次写入时传播者与生产者一致。 */
    val contentPropagator: String = contentPropagator ?: contentProducer
    val propagateId: String = propagateId ?: produceId

    fun toMap(): Map<String, String> = linkedMapOf(
        "Label" to label,
        "ContentProducer" to contentProducer,
        "ProduceID" to produceId,
        "ReservedCode1" to reservedCode1,
        "ContentPropagator" to contentPropagator,
        "PropagateID" to propagateId,
        "ReservedCode2" to reservedCode2,
    )

    fun toJsonString(): String {
        val o = JSONObject()
        toMap().forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }

    companion object {
        const val fieldName = "AIGC"

        private val keys = listOf(
            "Label", "ContentProducer", "ProduceID", "ReservedCode1", "ContentPropagator", "PropagateID", "ReservedCode2",
        )

        /** 没有任务编号时用地址摘要，保证同一文件可追溯。 */
        fun produceIdFor(contentId: String?, source: String): String {
            val id = contentId?.trim() ?: ""
            if (id.isNotEmpty()) return id
            return HostNative.crypto.sha256(source)
        }

        /** 只接受附录 E 的完整 JSON。主体或内容编号为空视为没有标识。 */
        fun tryParse(raw: String?): AigcLabel? {
            if (raw.isNullOrEmpty()) return null
            return try {
                val decoded = JSONObject(raw)
                val values = HashMap<String, String>()
                for (key in keys) {
                    val value = decoded.opt(key) as? String ?: return null
                    values[key] = value
                }
                if (values["Label"]!!.isEmpty() ||
                    values["ContentProducer"]!!.trim().isEmpty() ||
                    values["ProduceID"]!!.trim().isEmpty()
                ) return null
                AigcLabel(
                    label = values["Label"]!!,
                    contentProducer = values["ContentProducer"]!!,
                    produceId = values["ProduceID"]!!,
                    reservedCode1 = values["ReservedCode1"]!!,
                    contentPropagator = values["ContentPropagator"]!!,
                    propagateId = values["PropagateID"]!!,
                    reservedCode2 = values["ReservedCode2"]!!,
                )
            } catch (e: Throwable) {
                null
            }
        }

        /** 在文本里找明文 JSON 标识：定位 `"ContentProducer"`，回溯到最近的 `{` 并做括号配对。 */
        fun fromText(text: String): AigcLabel? {
            var start = 0
            while (true) {
                val hit = text.indexOf("\"ContentProducer\"", start)
                if (hit < 0) return null
                val open = text.lastIndexOf('{', hit)
                if (open >= 0) {
                    val end = matchingBrace(text, open)
                    if (end != null) {
                        val parsed = tryParse(text.substring(open, end + 1))
                        if (parsed != null) return parsed
                    }
                }
                start = hit + 1
            }
        }

        private fun matchingBrace(text: String, open: Int): Int? {
            var depth = 0
            var inString = false
            var escape = false
            for (i in open until text.length) {
                val char = text[i]
                if (inString) {
                    if (escape) {
                        escape = false
                        continue
                    }
                    if (char == '\\') {
                        escape = true
                        continue
                    }
                    if (char == '"') inString = false
                    continue
                }
                if (char == '"') {
                    inString = true
                    continue
                }
                if (char == '{') depth++
                if (char == '}') {
                    depth--
                    if (depth == 0) return i
                }
            }
            return null
        }
    }
}
