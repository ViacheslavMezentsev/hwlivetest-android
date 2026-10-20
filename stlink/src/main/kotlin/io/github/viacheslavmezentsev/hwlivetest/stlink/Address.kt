package io.github.viacheslavmezentsev.hwlivetest.stlink

object Address {
    /**
     * Разобрать адрес, введённый пользователем: шестнадцатеричный, с префиксом 0x или без,
     * допускаются «_» и пробелы как разделители (E000_ED00). Возвращает null при ошибке.
     */
    fun parse(text: String): Long? {
        val clean = text.trim().replace("_", "").replace(" ", "")
            .removePrefix("0x").removePrefix("0X")
        if (clean.isEmpty() || clean.length > 8) return null
        if (!clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return clean.toLong(16)
    }
}
