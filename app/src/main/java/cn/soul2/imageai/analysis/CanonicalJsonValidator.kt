package cn.soul2.imageai.analysis

import org.json.JSONArray
import org.json.JSONObject

internal object CanonicalJsonValidator {
    fun validateRawNesting(value: String) {
        var depth = 0
        var inString = false
        var escaped = false
        value.forEach { character ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth++
                        if (depth > CanonicalLimits.EXTENSION_JSON_MAX_DEPTH) {
                            invalid(
                                "extensionJson exceeds the " +
                                    "${CanonicalLimits.EXTENSION_JSON_MAX_DEPTH} level depth limit",
                            )
                        }
                    }
                    '}', ']' -> depth--
                }
            }
        }
    }

    fun validateParsedTree(value: Any?) {
        data class PendingValue(val value: Any?, val depth: Int)

        val pending = ArrayDeque<PendingValue>()
        pending.addLast(PendingValue(value, 1))
        var nodes = 0
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (current.depth > CanonicalLimits.EXTENSION_JSON_MAX_DEPTH) {
                invalid(
                    "extensionJson exceeds the " +
                        "${CanonicalLimits.EXTENSION_JSON_MAX_DEPTH} level depth limit",
                )
            }
            nodes++
            if (nodes > CanonicalLimits.EXTENSION_JSON_MAX_NODES) {
                invalid(
                    "extensionJson exceeds the " +
                        "${CanonicalLimits.EXTENSION_JSON_MAX_NODES} node limit",
                )
            }
            when (val parsed = current.value) {
                is JSONObject -> parsed.keys().forEach { key ->
                    pending.addLast(PendingValue(parsed.get(key), current.depth + 1))
                }
                is JSONArray -> repeat(parsed.length()) { index ->
                    pending.addLast(PendingValue(parsed.get(index), current.depth + 1))
                }
            }
        }
    }
}
