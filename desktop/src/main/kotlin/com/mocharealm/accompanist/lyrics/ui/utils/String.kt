/*
 * Vendored from Accompanist Lyrics (github.com/6xingyv/Accompanist), Apache License 2.0.
 * Lyrics UI 1.0.19 (the phone's) and the Lyrics Core 0.4.5 models it was built on; adapted only to build here.
 */
package com.mocharealm.accompanist.lyrics.ui.utils


fun Char.isJapanese(): Boolean {
    return this.code in 0x3040..0x309F || this.code in 0x30A0..0x30FF || this.code in 0xFF66..0xFF9F
}

fun Char.isKorean(): Boolean {
    return this.code in 0xAC00..0xD7AF || this.code in 0x1100..0x11FF
}

fun String.isPureCjk(): Boolean {
    val cleanedStr = this.filter { it != ' ' && it != ',' && it != '\n' && it != '\r' }
    if (cleanedStr.isEmpty()) {
        return false
    }
    return cleanedStr.all { it.isCjk() }
}

fun String.containsJapanese(): Boolean = any { it.isJapanese() }

fun String.containsKorean(): Boolean = any { it.isKorean() }

fun String.isRtl(): Boolean {
    return any { it.isArabic() }
}

fun String.isPunctuation(): Boolean {
    return isNotEmpty() && all { char ->
        char.isWhitespace() ||
                char in ".,!?;:\"'()[]{}…—–-、。，！？；：\"\"''（）【】《》～·" ||
                Character.getType(char) in setOf(
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt()
        )
    }
}

private val cjkBlocks: Set<Character.UnicodeBlock> by lazy {
    setOf(
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.HANGUL_SYLLABLES,
        Character.UnicodeBlock.HANGUL_JAMO,
        Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_G,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_H
    )
}

private val arabicBlocks: Set<Character.UnicodeBlock> by lazy {
    setOf(
        Character.UnicodeBlock.ARABIC,
        Character.UnicodeBlock.ARABIC_SUPPLEMENT,
        Character.UnicodeBlock.ARABIC_EXTENDED_A,
        Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A,
        Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B,
        Character.UnicodeBlock.ARABIC_EXTENDED_B
    )
}

private val devanagariBlocks: Set<Character.UnicodeBlock> by lazy {
    setOf(
        Character.UnicodeBlock.DEVANAGARI,
        Character.UnicodeBlock.DEVANAGARI_EXTENDED
    )
}

fun Char.isCjk(): Boolean {
    return Character.UnicodeBlock.of(this) in cjkBlocks
}

fun Char.isArabic(): Boolean {
    return Character.UnicodeBlock.of(this) in arabicBlocks
}

fun Char.isDevanagari(): Boolean {
    return Character.UnicodeBlock.of(this) in devanagariBlocks
}