package io.github.viacheslavmezentsev.hwlivetest.telemetry

/**
 * Минимальный разбор JSON (RFC 8259) без зависимостей, чтобы модуль оставался чистым
 * Kotlin/JVM. Результат: Map<String, Any?>, List<Any?>, String, Long, Double, Boolean, null.
 */
object Json {
    class ParseException(message: String) : IllegalArgumentException(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.atEnd()) throw p.error("лишние символы после JSON")
        return v
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun error(msg: String): ParseException {
            var line = 1
            var col = 1
            for (k in 0 until minOf(i, s.length)) {
                if (s[k] == '\n') {
                    line++
                    col = 1
                } else {
                    col++
                }
            }
            return ParseException("JSON, строка $line, позиция $col: $msg")
        }

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r' || s[i] == '﻿')) i++
        }

        fun value(): Any? {
            if (atEnd()) throw error("неожиданный конец")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw error("неожиданный символ '$c'")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw error("ожидалось $word")
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (peek('}')) return m
            while (true) {
                skipWs()
                if (atEnd() || s[i] != '"') throw error("ожидалось имя ключа")
                val k = str()
                skipWs()
                expect(':')
                skipWs()
                m[k] = value()
                skipWs()
                if (peek('}')) return m
                expect(',')
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (peek(']')) return l
            while (true) {
                skipWs()
                l += value()
                skipWs()
                if (peek(']')) return l
                expect(',')
            }
        }

        private fun str(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw error("незакрытая строка")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw error("незакрытая строка")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw error("короткая последовательность \\u")
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw error("неверная \\u"))
                                i += 4
                            }
                            else -> throw error("неверная экранировка \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return if (t.any { it in ".eE" }) {
                t.toDoubleOrNull() ?: throw error("неверное число $t")
            } else {
                t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw error("неверное число $t")
            }
        }

        private fun peek(c: Char): Boolean {
            if (i < s.length && s[i] == c) {
                i++
                return true
            }
            return false
        }

        private fun expect(c: Char) {
            if (!peek(c)) throw error("ожидалось '$c'")
        }
    }
}
