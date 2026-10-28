/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package android.content

import java.io.File
import java.util.Properties

/**
 * Just enough of Android's Context for the shared lyrics module to compile and run
 * on the desktop: a version name, and string preferences kept in a properties file.
 */
open class Context(private val dataDir: File, private val version: String) {
    open val applicationContext: Context get() = this
    open val packageName: String get() = "com.convx.desktop"
    open val packageManager: PackageManager get() = PackageManager(version)

    fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        SharedPreferences(File(dataDir, "$name.properties"))

    companion object {
        const val MODE_PRIVATE = 0
    }
}

class PackageManager(private val version: String) {
    fun getPackageInfo(packageName: String, flags: Int): PackageInfo = PackageInfo(version)
}

class PackageInfo(@JvmField val versionName: String?)

class SharedPreferences(private val file: File) {
    private val props = Properties().apply {
        if (file.isFile) file.inputStream().use(::load)
    }

    fun getString(key: String, defValue: String?): String? = props.getProperty(key) ?: defValue

    fun putString(key: String, value: String) {
        props.setProperty(key, value)
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, null) }
    }
}
