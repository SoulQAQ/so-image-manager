package cn.soul2.imageai.ai.protocol

import org.json.JSONArray
import org.json.JSONObject

class JsonPointer private constructor(
    private val segments: List<String>,
) {
    fun resolve(root: Any): Any {
        var current: Any = root
        segments.forEach { segment ->
            current = when (current) {
                is JSONObject -> if (current.has(segment)) {
                    current.get(segment)
                } else {
                    invalid("response pointer field is missing")
                }
                is JSONArray -> {
                    val index = segment.toIntOrNull()
                        ?: invalid("response pointer array segment is not an index")
                    if (index !in 0 until current.length()) {
                        invalid("response pointer array index is out of range")
                    }
                    current.get(index)
                }
                else -> invalid("response pointer traverses a scalar value")
            }
        }
        return current
    }

    companion object {
        fun parse(value: String): JsonPointer {
            if (value.length > 1_024) invalid("payload_pointer exceeds length limit")
            if (value.isEmpty()) return JsonPointer(emptyList())
            if (!value.startsWith('/')) invalid("payload_pointer must be an RFC 6901 pointer")
            val segments = value.drop(1).split('/').map(::decodeSegment)
            if (segments.size > 32) invalid("payload_pointer exceeds segment limit")
            return JsonPointer(segments)
        }

        private fun decodeSegment(segment: String): String {
            val output = StringBuilder(segment.length)
            var index = 0
            while (index < segment.length) {
                if (segment[index] != '~') {
                    output.append(segment[index++])
                    continue
                }
                if (index + 1 >= segment.length) invalid("payload_pointer contains invalid escape")
                output.append(
                    when (segment[index + 1]) {
                        '0' -> '~'
                        '1' -> '/'
                        else -> invalid("payload_pointer contains invalid escape")
                    },
                )
                index += 2
            }
            return output.toString()
        }
    }
}
