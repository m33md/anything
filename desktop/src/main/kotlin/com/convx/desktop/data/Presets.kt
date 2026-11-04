/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.data

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.Log
import com.convx.desktop.ui.components.PlayerIconSlot
import com.convx.desktop.ui.components.PlayerIcons
import com.convx.desktop.ui.player.DiyLayout
import com.convx.desktop.ui.player.DiyMaxAssetBytes
import com.convx.desktop.ui.player.DiyMaxStickers
import com.convx.desktop.ui.player.DiySticker
import com.convx.desktop.ui.player.DiyStickerKind
import com.convx.desktop.ui.player.DiyStore
import com.convx.desktop.ui.player.LyricsFonts
import com.convx.desktop.ui.theme.AppFont
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.svg.SVGDOM
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The parts of a look a preset can hold, by the phone's names so the files go both ways. The
 * phone's Colors and Background aren't here: Convx on the PC stays red on black and has no
 * wallpaper, so a phone preset's colours are left out when it's opened here.
 */
enum class PresetCategory(val label: String) {
    LAYOUT("Layout and sizing"),
    FONT("Font"),
    PLAYER("Player"),
    LYRICS("Lyrics"),
    GLASS("Liquid Glass"),
    PLAYER_ICONS("Player icons"),
    DIY("DIY stickers"),
}

/**
 * The phone's Presets: named snapshots of how the app looks, applied in parts and shared as a
 * .convxpreset file. A preset is a folder (manifest, thumbnail, assets) and a shared one is a zip
 * of it, laid out as the phone lays its out, so a preset made here opens on the phone and the
 * phone's open here. The manifest carries the phone's settings under their phone names, and this
 * PC's own under "desktop", which the phone doesn't read; whichever side made it, the other takes
 * what it has a place for.
 */
object Presets {
    private const val Tag = "Presets"
    const val Extension = "convxpreset"
    private const val Manifest = "manifest.json"
    /** The phone's name for it; the bytes are a PNG, which the phone reads all the same. */
    private const val Thumb = "thumb.webp"
    private const val Assets = "assets"
    private const val FormatVersion = 1
    private const val V2Prefix = "v2_"

    // The phone's import limits: a preset can come from anyone.
    private const val MaxArchiveBytes = 20L * 1024 * 1024
    private const val MaxEntries = 64
    private const val MaxEntryBytes = 25L * 1024 * 1024
    private const val MaxTotalBytes = 60L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false; coerceInputValues = true }

    data class Meta(
        val id: String,
        val name: String,
        val createdAt: Long,
        val categories: Set<PresetCategory>,
        /** Made on the phone: only the parts this PC has a place for come over. */
        val fromPhone: Boolean,
    )

    sealed interface ImportResult {
        data class Ok(val meta: Meta, val note: String?) : ImportResult
        data class Failed(val message: String) : ImportResult
    }

    val root: File get() = File(AppDirs.data, "presets").apply { mkdirs() }

    private val _all = MutableStateFlow<List<Meta>>(emptyList())
    val all: StateFlow<List<Meta>> = _all.asStateFlow()

    fun refresh() {
        _all.value = root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir -> readMeta(dir) }
            ?.sortedByDescending { it.createdAt }
            .orEmpty()
    }

    fun thumbnail(id: String): File? = File(File(root, id), Thumb).takeIf { it.isFile }

    /** This PC's settings, by field, that each part of a look is made of. Player icons and DIY have blocks of their own. */
    private val Fields: Map<PresetCategory, List<String>> = mapOf(
        PresetCategory.LAYOUT to listOf(
            "displayDensity", "gridSmall", "gridColumns", "gridCardHeight", "gridSpacing", "homeHeroCard", "homeHeroHeight",
            "homeCardCorner", "showHomeShuffle", "hideHomeFavoriteIcon", "libraryIconsOnly", "showAudioQualityBadge", "menuCorner",
        ),
        PresetCategory.FONT to listOf("appFont", "customFontEnabled", "customFont", "customFontName"),
        PresetCategory.PLAYER to listOf(
            "playerDesign", "playerLook", "playerBackground", "seekBarStyle", "playerSolidColor", "playerGradientStops",
            "playerGradientAngle", "artworkShadow", "showUpNext", "showVolumeBar", "hidePlayerArtwork", "playerArtworkCorner",
            "showCommentButton",
        ),
        PresetCategory.LYRICS to listOf(
            "lyricsStyle", "lyricsPosition", "lyricsGlow", "lyricsAppleBlur", "lyricsStandardBlur", "lyricsFont", "lyricsCustomFont",
            "lyricsTextSize", "lyricsLineSpacing", "lyricsWordSync", "lyricsAutoScroll", "lyricsSwipeToChange",
            "oneTapFullscreenLyrics", "fullscreenLyricsCollapseTop", "lyricsArtworkPlayPause",
        ),
        PresetCategory.GLASS to listOf("glass", "reduceLiveBlur"),
    )

    // ---- saving --------------------------------------------------------------------------------

    /** Records the parts of the current look in [categories] as a new preset. [thumbnail] is a PNG of the player. */
    fun capture(name: String, categories: Set<PresetCategory>, thumbnail: ByteArray?): Meta {
        val settings = AppSettings.value
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val assets = File(dir, Assets).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val all = json.encodeToJsonElement(Settings.serializer(), settings).jsonObject

        // Imported fonts travel as files, named so the phone takes them for fonts (it checks the extension).
        val fonts = LinkedHashMap<String, String>()
        fun keepFont(key: String, name: String) {
            if (name.isBlank() || name in fonts || !safeName(name)) return
            val source = LyricsFonts.dir.resolve(name).takeIf { it.isFile } ?: return
            val asset = "${key}_${name.substringBeforeLast('.')}.${fontExtension(source)}"
            runCatching { source.copyTo(File(assets, asset), overwrite = true) }.onSuccess { fonts[name] = asset }
        }
        if (PresetCategory.FONT in categories) keepFont("customFontPath", settings.customFont)
        if (PresetCategory.LYRICS in categories) {
            keepFont("lyricsFont", settings.lyricsCustomFont)
            if (settings.lyricsFont !in BuiltInLyricsFonts) keepFont("lyricsFont", settings.lyricsFont)
        }

        val manifest = buildJsonObject {
            put("version", FormatVersion)
            put("id", id)
            put("name", name)
            put("createdAt", now)
            putJsonArray("categories") { categories.forEach { add(JsonPrimitive(it.name)) } }
            put("prefs", PhonePrefs.write(all, categories, fonts[settings.customFont]))
            if (PresetCategory.PLAYER_ICONS in categories) {
                val (v1, v2) = settings.playerIcons.entries
                    .mapNotNull { (slot, icon) -> PlayerIconSlot.entries.firstOrNull { it.name == slot }?.let { it to icon } }
                    .partition { !it.first.isV2 }
                fun block(icons: List<Pair<PlayerIconSlot, PlayerIconOverride>>, prefix: String) = buildJsonObject {
                    icons.forEach { (slot, icon) ->
                        val source = PlayerIcons.file(icon).takeIf { it.isFile } ?: return@forEach
                        runCatching { source.copyTo(File(assets, prefix + icon.file), overwrite = true) }.onSuccess {
                            put(slot.name, buildJsonObject { put("file", icon.file); put("tint", icon.tint) })
                        }
                    }
                }
                put("playerIcons", block(v1, ""))
                put("v2PlayerIcons", block(v2, V2Prefix))
            }
            if (PresetCategory.DIY in categories) {
                val layout = settings.diyLayout
                layout.stickers.filter { it.kind == DiyStickerKind.IMAGE }.forEach { sticker ->
                    DiyStore.file(sticker.source)?.takeIf { it.isFile }?.let { runCatching { it.copyTo(File(assets, sticker.source), overwrite = true) } }
                }
                put("diy", json.encodeToJsonElement(DiyLayout.serializer(), layout))
            }
            put("desktop", JsonObject(categories.flatMap { Fields[it].orEmpty() }.mapNotNull { key -> all[key]?.let { key to it } }.toMap()))
            put("fonts", JsonObject(fonts.mapValues { JsonPrimitive(it.value) }))
        }
        File(dir, Manifest).writeText(manifest.toString())
        thumbnail?.let { runCatching { File(dir, Thumb).writeBytes(it) } }
        refresh()
        return Meta(id, name, now, categories, fromPhone = false)
    }

    fun rename(id: String, name: String) {
        val file = File(File(root, id), Manifest)
        runCatching {
            val manifest = json.parseToJsonElement(file.readText()).jsonObject
            file.writeText(JsonObject(manifest + ("name" to JsonPrimitive(name.take(60)))).toString())
        }.onFailure { Log.w(Tag, "Couldn't rename $id", it) }
        refresh()
    }

    fun delete(id: String) {
        if (safeName(id)) File(root, id).deleteRecursively()
        refresh()
    }

    /** A name for the shared file, the phone's way. */
    fun fileName(meta: Meta): String =
        "${meta.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "preset" }}.$Extension"

    /** Zips the preset to [target], ready to send to the phone or another PC. */
    fun export(meta: Meta, target: File) {
        val dir = File(root, meta.id)
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(dir).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    // ---- applying ------------------------------------------------------------------------------

    /** Puts the preset's [categories] in place; anything not ticked stays as it is. Returns what to tell the user. */
    fun apply(id: String, categories: Set<PresetCategory>): String {
        val dir = File(root, id)
        val manifest = runCatching { json.parseToJsonElement(File(dir, Manifest).readText()).jsonObject }.getOrNull()
            ?: return "This preset can't be read any more."
        val assets = File(dir, Assets)
        val desktop = manifest["desktop"] as? JsonObject ?: JsonObject(emptyMap())
        val fonts = (manifest["fonts"] as? JsonObject).orEmpty()
        val wanted = categories.flatMap { Fields[it].orEmpty() }.toSet()
        val updates = desktop.filterKeys { it in wanted }.toMutableMap()

        // Fonts are installed under this PC's own names, and the settings pointed at them.
        val installed = HashMap<String, String?>()
        for (key in listOf("customFont", "lyricsCustomFont", "lyricsFont")) {
            val name = (updates[key] as? JsonPrimitive)?.contentOrNull ?: continue
            val asset = (fonts[name] as? JsonPrimitive)?.contentOrNull ?: continue
            val local = installed.getOrPut(name) { installFont(File(assets, asset)) }
            if (local != null) {
                updates[key] = JsonPrimitive(local)
            } else {
                updates.remove(key)
                if (key == "lyricsFont") updates[key] = JsonPrimitive("default")
            }
        }

        val base = json.encodeToJsonElement(Settings.serializer(), AppSettings.value).jsonObject
        var merged = merge(base, updates)

        if (PresetCategory.PLAYER_ICONS in categories) {
            val now = System.currentTimeMillis()
            val icons = LinkedHashMap<String, PlayerIconOverride>()
            fun take(block: JsonObject?, prefix: String) {
                block?.forEach { (slotName, value) ->
                    val slot = PlayerIconSlot.entries.firstOrNull { it.name == slotName } ?: return@forEach
                    val entry = value as? JsonObject ?: return@forEach
                    val file = (entry["file"] as? JsonPrimitive)?.contentOrNull?.takeIf { safeName(it) } ?: return@forEach
                    val source = File(assets, prefix + file).takeIf { it.isFile } ?: return@forEach
                    val name = "${slot.name.lowercase()}.${source.extension.lowercase().ifEmpty { "png" }}"
                    runCatching { source.copyTo(File(PlayerIcons.dir, name), overwrite = true) }.onSuccess {
                        icons[slot.name] = PlayerIconOverride(name, (entry["tint"] as? JsonPrimitive)?.booleanOrNull ?: false, now)
                    }
                }
            }
            take(manifest["playerIcons"] as? JsonObject, "")
            take(manifest["v2PlayerIcons"] as? JsonObject, V2Prefix)
            merged = merged.copy(playerIcons = icons)
        }

        if (PresetCategory.DIY in categories) {
            val layout = (manifest["diy"])?.let { runCatching { json.decodeFromJsonElement(DiyLayout.serializer(), it) }.getOrNull() }
            if (layout != null) {
                var budget = DiyMaxAssetBytes
                val kept = layout.stickers.take(DiyMaxStickers).mapNotNull { sticker ->
                    if (sticker.kind == DiyStickerKind.IMAGE) {
                        val source = File(assets, sticker.source).takeIf { it.isFile && safeName(sticker.source) } ?: return@mapNotNull null
                        val target = DiyStore.file(sticker.source) ?: return@mapNotNull null
                        if (source.length() > budget) return@mapNotNull null
                        budget -= source.length()
                        if (runCatching { source.copyTo(target, overwrite = true) }.isFailure) return@mapNotNull null
                    }
                    sticker.sane()
                }
                merged = merged.copy(diyLayout = DiyLayout(stickers = kept))
            }
        }

        val result = merged
        AppSettings.update { current ->
            // Only the look changes: what plays, the window and the accounts are this PC's own.
            result.copy(
                volume = current.volume, muted = current.muted, shuffle = current.shuffle, repeat = current.repeat,
                lastQueue = current.lastQueue, lastIndex = current.lastIndex, lastPositionMs = current.lastPositionMs,
                windowWidth = current.windowWidth, windowHeight = current.windowHeight, windowMaximized = current.windowMaximized,
            )
        }
        if (PresetCategory.PLAYER_ICONS in categories) PlayerIcons.prune()
        if (PresetCategory.DIY in categories) DiyStore.prune(AppSettings.value.diyLayout)
        return "Preset applied"
    }

    /** [base] with [updates] laid over it, objects field by field; a value this PC can't read leaves the current one. */
    private fun merge(base: JsonObject, updates: Map<String, JsonElement>): Settings {
        fun over(into: JsonObject, key: String, value: JsonElement): JsonObject {
            val old = into[key]
            val next = if (old is JsonObject && value is JsonObject) JsonObject(old + value) else value
            return JsonObject(into + (key to next))
        }
        fun decode(obj: JsonObject) = runCatching { json.decodeFromJsonElement(Settings.serializer(), obj) }.getOrNull()
        val all = updates.entries.fold(base) { acc, (key, value) -> over(acc, key, value) }
        decode(all)?.let { return it }
        var good = base
        updates.forEach { (key, value) ->
            val next = over(good, key, value)
            if (decode(next) != null) good = next else Log.w(Tag, "Skipped \"$key\": this PC can't read its value")
        }
        return decode(good) ?: AppSettings.value
    }

    private fun DiySticker.sane() = copy(
        z = z.coerceIn(-DiySticker.MaxZ, DiySticker.MaxZ),
        opacity = opacity.coerceIn(0.05f, 1f),
        cornerRadius = cornerRadius.coerceIn(0f, 0.5f),
        portrait = portrait.clamped(),
        landscape = landscape.clamped(),
    )

    /** Copies a font in, reusing one already here with the same bytes; null if it isn't a font. */
    private fun installFont(source: File): String? {
        if (!source.isFile) return null
        val bytes = runCatching { source.readBytes() }.getOrNull() ?: return null
        LyricsFonts.dir.listFiles()
            ?.firstOrNull { it.isFile && it.length() == bytes.size.toLong() && it.name.matches(Regex("custom-\\d+\\.font")) && it.readBytes().contentEquals(bytes) }
            ?.let { return it.name }
        return runCatching { LyricsFonts.import(source) }.onFailure { Log.w(Tag, "A preset's font didn't install", it) }.getOrNull()
    }

    private fun fontExtension(file: File): String {
        val head = runCatching { file.inputStream().use { input -> ByteArray(4).also { input.read(it) } } }.getOrNull()
        return if (head != null && String(head, Charsets.ISO_8859_1) == "OTTO") "otf" else "ttf"
    }

    private val BuiltInLyricsFonts = setOf("default", "rounded", "serif", "monospace")

    // ---- opening a shared preset ---------------------------------------------------------------

    /** Opens a .convxpreset from this PC or the phone. Everything in it is checked as if it came from a stranger. */
    fun import(file: File): ImportResult {
        if (file.length() > MaxArchiveBytes) return ImportResult.Failed("That preset is too large to import.")
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        fun fail(message: String): ImportResult {
            dir.deleteRecursively()
            return ImportResult.Failed(message)
        }
        val unpacked = runCatching { unpack(file, dir) }.getOrElse { Log.w(Tag, "Couldn't unpack ${file.name}", it); false }
        if (!unpacked) return fail("That file is not a valid preset, or it's too large.")
        val source = runCatching { json.parseToJsonElement(File(dir, Manifest).readText()).jsonObject }.getOrNull()
            ?: return fail("That file is not a valid preset.")
        if (((source["version"] as? JsonPrimitive)?.intOrNull ?: 0) > FormatVersion) return fail("That preset was made with a newer version of Convx.")

        val named = (source["categories"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        val categories = PresetCategory.entries.filter { it.name in named }.toSet()
        val fromPhone = source["desktop"] !is JsonObject
        val notes = mutableListOf<String>()
        var manifest = source
        if (fromPhone) {
            // The phone's settings, turned into this PC's; the file stays as it was for sending on.
            val (desktop, fonts) = PhonePrefs.read(source["prefs"] as? JsonArray, categories)
            manifest = JsonObject(
                manifest + mapOf(
                    "desktop" to desktop,
                    "fonts" to JsonObject(fonts.mapValues { JsonPrimitive(it.value) }),
                    "source" to JsonPrimitive("phone"),
                ),
            )
            if ("COLORS" in named || "BACKGROUND" in named) notes += "Colours and wallpaper stay as they are, since Convx on the PC is always red on black."
        }
        val name = (source["name"] as? JsonPrimitive)?.contentOrNull?.take(60)?.ifBlank { null } ?: "Preset"
        val createdAt = (source["createdAt"] as? JsonPrimitive)?.longOrNull ?: System.currentTimeMillis()
        manifest = JsonObject(manifest + mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(name), "createdAt" to JsonPrimitive(createdAt)))
        File(dir, Manifest).writeText(manifest.toString())
        sanitise(dir, manifest)
        if (categories.isEmpty()) return fail("There's nothing in this preset that Convx on the PC can use.")
        refresh()
        return ImportResult.Ok(Meta(id, name, createdAt, categories, fromPhone), notes.firstOrNull())
    }

    /** False if the archive breaks a limit or holds anything but a preset's own files. */
    private fun unpack(file: File, dir: File): Boolean {
        val rootPath = dir.canonicalPath
        var entries = 0
        var total = 0L
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MaxEntries) return false
                if (entry.isDirectory) continue
                if (!safeEntry(entry.name)) return false
                val target = File(dir, entry.name)
                if (!target.canonicalPath.startsWith(rootPath + File.separator)) return false
                target.parentFile?.mkdirs()
                var written = 0L
                target.outputStream().buffered().use { out ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        written += read
                        total += read
                        if (written > MaxEntryBytes || total > MaxTotalBytes) return false
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
        return File(dir, Manifest).isFile
    }

    /** Deletes every asset the manifest doesn't name, and every picture or thumbnail that doesn't read as one. */
    private fun sanitise(dir: File, manifest: JsonObject) {
        val referenced = buildSet {
            (manifest["fonts"] as? JsonObject)?.values?.forEach { (it as? JsonPrimitive)?.contentOrNull?.let { add(it) } }
            (manifest["prefs"] as? JsonArray)?.forEach { entry ->
                ((entry as? JsonObject)?.get("v") as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("asset:") }?.let { add(it.removePrefix("asset:")) }
            }
            listOf("playerIcons" to "", "v2PlayerIcons" to V2Prefix).forEach { (block, prefix) ->
                (manifest[block] as? JsonObject)?.values?.forEach { icon ->
                    ((icon as? JsonObject)?.get("file") as? JsonPrimitive)?.contentOrNull?.let { add(prefix + it) }
                }
            }
            ((manifest["diy"] as? JsonObject)?.get("stickers") as? JsonArray)?.take(DiyMaxStickers)?.forEach { sticker ->
                val obj = sticker as? JsonObject ?: return@forEach
                if ((obj["kind"] as? JsonPrimitive)?.contentOrNull == DiyStickerKind.IMAGE.name) {
                    (obj["source"] as? JsonPrimitive)?.contentOrNull?.let { add(it) }
                }
            }
        }
        File(dir, Assets).listFiles()?.forEach { file ->
            val keep = file.name in referenced && when (file.extension.lowercase()) {
                "ttf", "otf" -> runCatching { LyricsFonts.check(file) }.isSuccess
                "svg" -> runCatching { SVGDOM(Data.makeFromBytes(file.readBytes())).root != null }.getOrDefault(false)
                else -> readsAsImage(file)
            }
            if (!keep) file.delete()
        }
        File(dir, Thumb).takeIf { it.isFile && !readsAsImage(it) }?.delete()
    }

    private fun readsAsImage(file: File) = runCatching { SkiaImage.makeFromEncoded(file.readBytes()).width > 0 }.getOrDefault(false)

    private fun safeEntry(name: String): Boolean {
        if (name.isEmpty() || name.length > 256 || name.startsWith("/") || ".." in name || '\\' in name || ':' in name) return false
        val parts = name.split('/')
        return when (parts.size) {
            1 -> parts[0] == Manifest || parts[0] == Thumb
            2 -> parts[0] == Assets && safeName(parts[1])
            else -> false
        }
    }

    private fun safeName(name: String) = name.isNotEmpty() && name.length <= 128 && '/' !in name && '\\' !in name && ".." !in name && ':' !in name

    private fun readMeta(dir: File): Meta? = runCatching {
        val manifest = json.parseToJsonElement(File(dir, Manifest).readText()).jsonObject
        val named = (manifest["categories"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        Meta(
            id = dir.name,
            name = (manifest["name"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null } ?: "Preset",
            createdAt = (manifest["createdAt"] as? JsonPrimitive)?.longOrNull ?: dir.lastModified(),
            categories = PresetCategory.entries.filter { it.name in named }.toSet(),
            fromPhone = (manifest["source"] as? JsonPrimitive)?.contentOrNull == "phone",
        )
    }.getOrNull()

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}

/**
 * The phone's preference names and types for the settings both apps have, and how a value
 * crosses: enums by the phone's order, colours from Android's signed ints, the volume bar's
 * "hide" the other way up. Settings only one side has aren't listed and stay put.
 */
private object PhonePrefs {
    private class Pref(
        val name: String,
        val type: String,
        val category: PresetCategory,
        /** This PC's field, or "glass.field" inside the glass settings. */
        val path: String,
        val toDesktop: (JsonPrimitive) -> JsonElement? = { it },
        val toPhone: (JsonElement) -> JsonElement? = { it },
    )

    private fun bool(name: String, category: PresetCategory, path: String, invert: Boolean = false) = Pref(
        name, "BOOL", category, path,
        toDesktop = { v -> v.booleanOrNull?.let { JsonPrimitive(it != invert) } },
        toPhone = { v -> (v as? JsonPrimitive)?.booleanOrNull?.let { JsonPrimitive(it != invert) } },
    )

    private fun int(name: String, category: PresetCategory, path: String) = Pref(
        name, "INT", category, path,
        toDesktop = { v -> v.intOrNull?.let(::JsonPrimitive) },
        toPhone = { v -> (v as? JsonPrimitive)?.intOrNull?.let(::JsonPrimitive) },
    )

    private fun float(name: String, category: PresetCategory, path: String, range: ClosedFloatingPointRange<Double>? = null) = Pref(
        name, "FLOAT", category, path,
        toDesktop = { v -> v.doubleOrNull?.let { JsonPrimitive(if (range != null) it.coerceIn(range) else it) } },
        toPhone = { v -> (v as? JsonPrimitive)?.doubleOrNull?.let(::JsonPrimitive) },
    )

    /** An enum the two apps list in the same order under different names. */
    private fun choice(name: String, category: PresetCategory, path: String, phone: List<String>, desktop: List<String>): Pref {
        check(phone.size == desktop.size) { "$name lists don't line up" }
        return Pref(
            name, "STRING", category, path,
            toDesktop = { v -> phone.indexOf(v.contentOrNull).takeIf { it >= 0 }?.let { JsonPrimitive(desktop[it]) } },
            toPhone = { v -> desktop.indexOf((v as? JsonPrimitive)?.contentOrNull).takeIf { it >= 0 }?.let { JsonPrimitive(phone[it]) } },
        )
    }

    private fun argb(value: Long) = JsonPrimitive(value.toInt())
    private fun unsigned(value: Int) = value.toLong() and 0xFFFFFFFFL

    private val All: List<Pref> = listOf(
        // Layout and sizing
        float("density_scale_factor", PresetCategory.LAYOUT, "displayDensity", 0.5..1.5),
        Pref(
            "gridItemSize", "STRING", PresetCategory.LAYOUT, "gridSmall",
            toDesktop = { v -> JsonPrimitive(v.contentOrNull == "SMALL") },
            toPhone = { v -> JsonPrimitive(if ((v as? JsonPrimitive)?.booleanOrNull == true) "SMALL" else "BIG") },
        ),
        int("gridColumnsOverride", PresetCategory.LAYOUT, "gridColumns"),
        int("gridCardHeightOverrideDp", PresetCategory.LAYOUT, "gridCardHeight"),
        int("gridSpacingDp", PresetCategory.LAYOUT, "gridSpacing"),
        bool("homeHeroCardEnabled", PresetCategory.LAYOUT, "homeHeroCard"),
        int("homeHeroCardHeightOverrideDp", PresetCategory.LAYOUT, "homeHeroHeight"),
        int("homeCardCornerRadiusOverrideDp", PresetCategory.LAYOUT, "homeCardCorner"),
        bool("showHomeFab", PresetCategory.LAYOUT, "showHomeShuffle"),
        bool("libraryIconsOnly", PresetCategory.LAYOUT, "libraryIconsOnly"),
        bool("show_audio_quality_badge", PresetCategory.LAYOUT, "showAudioQualityBadge"),
        // Font (the file itself is handled with the assets)
        Pref(
            "selected_font", "STRING", PresetCategory.FONT, "appFont",
            toDesktop = { v -> v.contentOrNull?.takeIf { id -> AppFont.entries.any { it.id == id } }?.let(::JsonPrimitive) },
        ),
        bool("customFontEnabled", PresetCategory.FONT, "customFontEnabled"),
        Pref(
            "customFontName", "STRING", PresetCategory.FONT, "customFontName",
            toDesktop = { v -> v.contentOrNull?.take(80)?.let(::JsonPrimitive) },
        ),
        // Player
        choice(
            "playerBackgroundStyle", PresetCategory.PLAYER, "playerBackground",
            listOf("DEFAULT", "GRADIENT", "BLUR", "GLOW_ANIMATED", "APPLE_MUSIC", "LIVE_MESH", "FLUID_MESH", "STATIC", "CUSTOM_GRADIENT", "GLASS"),
            PlayerBackground.entries.map { it.name },
        ),
        choice("sliderStyle", PresetCategory.PLAYER, "seekBarStyle", listOf("DEFAULT", "WAVY", "SLIM", "WAVEFORM"), SeekBarStyle.entries.map { it.name }),
        Pref(
            "playerStaticColor", "INT", PresetCategory.PLAYER, "playerSolidColor",
            toDesktop = { v -> v.intOrNull?.let { JsonPrimitive(unsigned(it)) } },
            toPhone = { v -> (v as? JsonPrimitive)?.longOrNull?.let { argb(it) } },
        ),
        Pref(
            "playerGradientStops", "STRING", PresetCategory.PLAYER, "playerGradientStops",
            toDesktop = { v ->
                v.contentOrNull?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size >= 2 }
                    ?.let { stops -> JsonArray(stops.take(8).map { JsonPrimitive(unsigned(it)) }) }
            },
            toPhone = { v ->
                (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull?.toInt() }?.takeIf { it.size >= 2 }
                    ?.let { JsonPrimitive(it.joinToString(",")) }
            },
        ),
        float("playerGradientAngle", PresetCategory.PLAYER, "playerGradientAngle"),
        bool("hidePlayerThumbnail", PresetCategory.PLAYER, "hidePlayerArtwork"),
        bool("hideVolumeBar", PresetCategory.PLAYER, "showVolumeBar", invert = true),
        // The phone's Card, Vinyl and Clover; its Apple Music designs aren't in its presets, so only these go over.
        Pref(
            "playerArtworkStyle", "STRING", PresetCategory.PLAYER, "playerDesign",
            toDesktop = { null },
            toPhone = { v -> (v as? JsonPrimitive)?.contentOrNull?.takeIf { it in setOf("Card", "Vinyl", "Clover") }?.let { JsonPrimitive(it.uppercase()) } },
        ),
        // Lyrics
        choice("lyricsTextPosition", PresetCategory.LYRICS, "lyricsPosition", listOf("LEFT", "CENTER", "RIGHT"), LyricsPosition.entries.map { it.name }),
        bool("lyricsGlowEffect", PresetCategory.LYRICS, "lyricsGlow"),
        bool("appleMusicLyricsBlur", PresetCategory.LYRICS, "lyricsAppleBlur"),
        bool("lyricsStandardBlur", PresetCategory.LYRICS, "lyricsStandardBlur"),
        choice(
            "lyricsAnimationStyle", PresetCategory.LYRICS, "lyricsStyle",
            listOf("FLAMINGO", "NONE", "FADE", "GLOW", "SLIDE", "KARAOKE", "APPLE", "APPLE_V2", "BITCHORD", "CONVX_1", "LYRICS_V2", "METRO_LYRICS", "ARCHIVE_TUNE"),
            LyricsStyle.entries.map { it.name },
        ),
        float("lyricsTextSize", PresetCategory.LYRICS, "lyricsTextSize", 18.0..60.0),
        float("lyricsLineSpacing", PresetCategory.LYRICS, "lyricsLineSpacing", 1.0..2.5),
        bool("oneTapFullscreenLyrics", PresetCategory.LYRICS, "oneTapFullscreenLyrics"),
        bool("fullscreenLyricsCollapseTop", PresetCategory.LYRICS, "fullscreenLyricsCollapseTop"),
        // Liquid Glass
        float("liquidGlassBlurRadius", PresetCategory.GLASS, "glass.blurRadius", 0.0..64.0),
        float("liquidGlassVibrancy", PresetCategory.GLASS, "glass.vibrancy", 0.0..2.0),
        float("liquidGlassLensHeight", PresetCategory.GLASS, "glass.lensHeight", 0.0..1.0),
        float("liquidGlassLensAmount", PresetCategory.GLASS, "glass.lensAmount", 0.0..1.0),
        bool("liquidGlassChromaticAberration", PresetCategory.GLASS, "glass.chromaticAberration"),
        bool("liquidGlassDepthEffect", PresetCategory.GLASS, "glass.depthEffect"),
        float("liquidGlassSurfaceOpacity", PresetCategory.GLASS, "glass.surfaceOpacity", 0.0..1.0),
        float("liquidGlassHighlightOpacity", PresetCategory.GLASS, "glass.highlightOpacity", 0.0..1.0),
    )

    private fun entry(name: String, type: String, value: JsonElement) = buildJsonObject {
        put("n", name)
        put("t", type)
        put("v", value)
    }

    /** The phone's "prefs" for this PC's [settings] (as JSON), in [categories]; [fontAsset] is the app font's file in the preset. */
    fun write(settings: JsonObject, categories: Set<PresetCategory>, fontAsset: String?): JsonArray = JsonArray(buildList {
        All.filter { it.category in categories }.forEach { pref ->
            val value = valueAt(settings, pref.path) ?: return@forEach
            pref.toPhone(value)?.takeIf { it !is JsonNull }?.let { add(entry(pref.name, pref.type, it)) }
        }
        if (PresetCategory.FONT in categories && fontAsset != null) add(entry("customFontPath", "STRING", JsonPrimitive("asset:$fontAsset")))
    })

    /** This PC's settings from a phone preset's "prefs", and the fonts it brings (setting value to asset). */
    fun read(prefs: JsonArray?, categories: Set<PresetCategory>): Pair<JsonObject, Map<String, String>> {
        val desktop = LinkedHashMap<String, JsonElement>()
        val glass = LinkedHashMap<String, JsonElement>()
        val fonts = LinkedHashMap<String, String>()
        prefs?.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val name = (obj["n"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            val type = (obj["t"] as? JsonPrimitive)?.contentOrNull
            val value = obj["v"] as? JsonPrimitive ?: return@forEach
            if (name == "customFontPath" && PresetCategory.FONT in categories && type == "STRING") {
                val asset = value.contentOrNull?.takeIf { it.startsWith("asset:") }?.removePrefix("asset:") ?: return@forEach
                fonts[asset] = asset
                desktop["customFont"] = JsonPrimitive(asset)
                return@forEach
            }
            // A preset that disagrees about a setting's type is malformed or hostile; the phone drops those too.
            val pref = All.firstOrNull { it.name == name && it.type == type && it.category in categories } ?: return@forEach
            val converted = pref.toDesktop(value) ?: return@forEach
            if (pref.path.startsWith("glass.")) glass[pref.path.removePrefix("glass.")] = converted else desktop[pref.path] = converted
        }
        if (glass.isNotEmpty()) desktop["glass"] = JsonObject(glass)
        return JsonObject(desktop) to fonts
    }

    private fun valueAt(settings: JsonObject, path: String): JsonElement? =
        if (path.startsWith("glass.")) (settings["glass"] as? JsonObject)?.get(path.removePrefix("glass.")) else settings[path]
}
