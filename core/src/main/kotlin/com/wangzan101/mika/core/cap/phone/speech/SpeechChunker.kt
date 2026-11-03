package com.wangzan101.mika.core.cap.phone.speech

/**
 * Splits a reply into sentences so the first sentence can be synthesized
 * before the rest of the text is sent. A long sentence is cut on a comma
 * so the first playable piece stays short.
 */
object SpeechChunker {
    const val MAX_CHARS = 80

    fun chunks(text: String): List<String> {
        val cleaned = text.replace("\r\n", "\n").trim()
        if (cleaned.isEmpty()) return emptyList()
        val sentences = mutableListOf<String>()
        val buf = StringBuilder()
        for (i in cleaned.indices) {
            val c = cleaned[i]
            buf.append(c)
            if (endsSentence(cleaned, i)) {
                val piece = buf.toString().trim()
                buf.clear()
                if (piece.isNotEmpty()) sentences += piece
            }
        }
        val tail = buf.toString().trim()
        if (tail.isNotEmpty()) sentences += tail
        return sentences.flatMap(::splitLong)
    }

    private fun endsSentence(s: String, i: Int): Boolean {
        val c = s[i]
        if (c == '\n' || c in "。！？!?；;" || c == '…') return true
        if (c != '.') return false
        if (isDecimalDot(s, i)) return false
        return s.getOrNull(i + 1) != '.'
    }

    private fun isDecimalDot(s: String, i: Int): Boolean {
        val prev = s.getOrNull(i - 1) ?: return false
        val next = s.getOrNull(i + 1) ?: return false
        return prev.isDigit() && next.isDigit()
    }

    private fun splitLong(sentence: String): List<String> {
        if (sentence.length <= MAX_CHARS) return listOf(sentence)
        val parts = mutableListOf<String>()
        val buf = StringBuilder()
        for (c in sentence) {
            buf.append(c)
            val soft = (c == '，' || c == '、' || c == ',') && buf.length >= 12
            if (soft || buf.length >= MAX_CHARS) {
                val piece = buf.toString().trim()
                buf.clear()
                if (piece.isNotEmpty()) parts += piece
            }
        }
        val tail = buf.toString().trim()
        if (tail.isNotEmpty()) parts += tail
        return parts
    }
}
