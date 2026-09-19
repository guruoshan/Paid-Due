package com.dailymoneyregister.core.serialization

/** Shared escaping primitives for durable key=value envelopes. */
internal object DurableEnvelopeCodec {
    fun escape(value: String): String {
        val result = StringBuilder(value.length)
        value.forEach { character ->
            when (character) {
                '\\' -> result.append("\\\\")
                '\n' -> result.append("\\n")
                '\r' -> result.append("\\r")
                else -> result.append(character)
            }
        }
        return result.toString()
    }

    fun unescape(value: String): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            if (value[index] == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    'n' -> { result.append('\n'); index += 2; continue }
                    'r' -> { result.append('\r'); index += 2; continue }
                    '\\' -> { result.append('\\'); index += 2; continue }
                }
            }
            result.append(value[index])
            index++
        }
        return result.toString()
    }
}