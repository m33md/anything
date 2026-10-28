/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package android.util

/** The two corners of Android's Base64 the shared lyrics code uses, on java.util.Base64. */
object Base64 {
    const val DEFAULT = 0
    const val NO_WRAP = 2

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String =
        if (flags and NO_WRAP != 0) java.util.Base64.getEncoder().encodeToString(input)
        else java.util.Base64.getMimeEncoder().encodeToString(input) + "\n"

    @JvmStatic
    fun decode(input: String, flags: Int): ByteArray = java.util.Base64.getMimeDecoder().decode(input)
}
