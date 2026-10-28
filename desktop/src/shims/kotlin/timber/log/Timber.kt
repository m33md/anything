/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package timber.log

/** Desktop stand-in for Timber: the shared modules log through it, the desktop app prints. */
object Timber {
    @Volatile
    var verbose: Boolean = System.getProperty("convx.debug") != null

    class Tree internal constructor(private val tag: String?) {
        fun v(message: String?, vararg args: Any?) = log("V", null, message, args)
        fun d(message: String?, vararg args: Any?) = log("D", null, message, args)
        fun i(message: String?, vararg args: Any?) = log("I", null, message, args)
        fun w(message: String?, vararg args: Any?) = log("W", null, message, args)
        fun e(message: String?, vararg args: Any?) = log("E", null, message, args)
        fun v(t: Throwable?, message: String?, vararg args: Any?) = log("V", t, message, args)
        fun d(t: Throwable?, message: String?, vararg args: Any?) = log("D", t, message, args)
        fun i(t: Throwable?, message: String?, vararg args: Any?) = log("I", t, message, args)
        fun w(t: Throwable?, message: String?, vararg args: Any?) = log("W", t, message, args)
        fun e(t: Throwable?, message: String?, vararg args: Any?) = log("E", t, message, args)
        fun w(t: Throwable?) = log("W", t, null, emptyArray())
        fun e(t: Throwable?) = log("E", t, null, emptyArray())

        private fun log(level: String, t: Throwable?, message: String?, args: Array<out Any?>) {
            if (!verbose && level != "E" && level != "W") return
            val text = if (message != null && args.isNotEmpty()) {
                runCatching { message.format(*args) }.getOrDefault(message)
            } else message
            System.err.println("$level/${tag ?: "Convx"}: ${text.orEmpty()}")
            if (t != null && verbose) t.printStackTrace()
        }
    }

    private val root = Tree(null)

    fun tag(tag: String): Tree = Tree(tag)

    fun v(message: String?, vararg args: Any?) = root.v(message, *args)
    fun d(message: String?, vararg args: Any?) = root.d(message, *args)
    fun i(message: String?, vararg args: Any?) = root.i(message, *args)
    fun w(message: String?, vararg args: Any?) = root.w(message, *args)
    fun e(message: String?, vararg args: Any?) = root.e(message, *args)
    fun v(t: Throwable?, message: String?, vararg args: Any?) = root.v(t, message, *args)
    fun d(t: Throwable?, message: String?, vararg args: Any?) = root.d(t, message, *args)
    fun i(t: Throwable?, message: String?, vararg args: Any?) = root.i(t, message, *args)
    fun w(t: Throwable?, message: String?, vararg args: Any?) = root.w(t, message, *args)
    fun e(t: Throwable?, message: String?, vararg args: Any?) = root.e(t, message, *args)
    fun w(t: Throwable?) = root.w(t)
    fun e(t: Throwable?) = root.e(t)
}
