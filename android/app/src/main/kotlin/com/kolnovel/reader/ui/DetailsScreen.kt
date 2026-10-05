package com.kolnovel.reader.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kolnovel.reader.data.ChapterRef
import com.kolnovel.reader.data.ChapterStore
import com.kolnovel.reader.data.Downloads
import com.kolnovel.reader.data.LibraryStore
import com.kolnovel.reader.data.NovelDetails
import com.kolnovel.reader.data.NovelStore
import com.kolnovel.reader.data.NovelSummary

private object DetailsCache {
    val map = object : LinkedHashMap<String, NovelDetails>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NovelDetails>?) = size > 20
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailsScreen(novel: NovelSummary) {
    val services = LocalServices.current
    val colors = LocalAppColors.current
    val context = LocalContext.current
    var details by remember(novel.url) { mutableStateOf(DetailsCache.map[novel.url]) }
    var offline by remember(novel.url) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(novel.url, reload) {
        error = null
        try {
            val d = services.source.details(novel.url)
            DetailsCache.map[novel.url] = d
            details = d
            offline = false
            LibraryStore.noteChapterCount(novel.url, d.chapters.size)
            NovelStore.save(d, novel.cover)
        } catch (e: Exception) {
            // No internet: fall back to what was saved when chapters were downloaded.
            val saved = NovelStore.load(novel.url)
            if (details == null && saved != null) {
                details = saved.toDetails()
                offline = true
            } else if (details == null) {
                error = e.message ?: e.toString()
            }
        }
    }
    val entry = LibraryStore.entries[novel.url]
    LaunchedEffect(novel.url) { LibraryStore.clearNew(novel.url) }

    val d = details
    val summary = if (d != null) novel.copy(title = d.title.ifBlank { novel.title }, cover = d.cover ?: novel.cover) else novel
    var newestFirst by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var synopsisOpen by remember { mutableStateOf(false) }
    var selection by remember(novel.url) { mutableStateOf<Set<String>?>(null) }
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
        // Blurred cover across the top, fading into the background.
        RemoteImage(summary.cover, Modifier.fillMaxWidth().height(300.dp).blur(40.dp))
        Box(
            Modifier.fillMaxWidth().height(302.dp).background(
                Brush.verticalGradient(listOf(colors.background.copy(alpha = 0.35f), colors.background))
            )
        )
        LazyColumn(
            Modifier.fillMaxSize(), state = listState,
            contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, if (selection != null) 90.dp else 24.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.glass(colors, RoundedCornerShape(16.dp)).padding(5.dp)) {
                        RemoteImage(summary.cover, Modifier.width(118.dp).aspectRatio(0.7f).clip(RoundedCornerShape(12.dp)))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(summary.title, color = colors.text, fontWeight = FontWeight.Black, fontSize = 19.sp, lineHeight = 26.sp)
                        d?.altTitle?.let { Text(it, color = colors.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        Gap(6)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            d?.status?.let { Pill(it, colors.accent, colors.onAccent) }
                            (d?.rating ?: novel.rating)?.let { RatingTag(it) }
                            d?.let { Text("${it.chapters.size} فصل", color = colors.muted, fontSize = 13.sp) }
                        }
                        if (offline) {
                            Gap(6)
                            Text("بدون اتصال: تعرض النسخة المحفوظة", color = colors.accent, fontSize = 12.sp)
                        }
                    }
                }
                Gap(10)
                if (d != null && (d.info.isNotEmpty() || d.genres.isNotEmpty())) {
                    GlassPanel(Modifier.fillMaxWidth(), padding = 10.dp) {
                        d.info.forEach { (k, v) ->
                            Row(Modifier.padding(vertical = 1.dp)) {
                                Text("$k: ", color = colors.muted, fontSize = 13.sp)
                                Text(v, color = colors.text, fontSize = 13.sp)
                            }
                        }
                        if (d.genres.isNotEmpty()) {
                            Gap(6)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                d.genres.forEach { g -> GlassChip(g) }
                            }
                        }
                    }
                    Gap(10)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (d != null && d.chapters.isNotEmpty()) {
                        val resume = entry?.lastChapterUrl
                        if (resume != null) {
                            AccentButton("تابع: ${entry.lastChapterTitle.orEmpty()}".take(32), Icons.Filled.PlayArrow, {
                                services.nav.go(Screen.Reader(summary, resume, entry.lastParagraph))
                            })
                            GlassButton("من البداية", onClick = { services.nav.go(Screen.Reader(summary, d.chapters.first().url)) })
                        } else {
                            AccentButton("ابدأ القراءة", Icons.Filled.PlayArrow, {
                                services.nav.go(Screen.Reader(summary, d.chapters.first().url))
                            })
                        }
                    }
                    val inLib = entry?.inLibrary == true
                    GlassButton(
                        if (inLib) "في مكتبتي" else "أضف لمكتبتي",
                        if (inLib) Icons.Filled.Check else Icons.Filled.Add,
                        onClick = { LibraryStore.toggleLibrary(summary) },
                        tint = if (inLib) colors.accent else null,
                    )
                    GlassButton(
                        "مفضلة",
                        if (entry?.favorite == true) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        onClick = { LibraryStore.toggleFavorite(summary) },
                        tint = if (entry?.favorite == true) colors.accent else null,
                    )
                    GlassButton("الموقع", Icons.Filled.Share, onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(novel.url))) }
                    })
                    GlassButton("تحديث", Icons.Filled.Refresh, onClick = { reload++ })
                }
                if (d != null && d.chapters.isNotEmpty()) {
                    Gap(12)
                    DownloadPanel(summary, d)
                }
            }
            if (d == null) {
                item { if (error != null) ErrorBox(error!!, onRetry = { reload++ }) else Loading() }
                return@LazyColumn
            }
            if (d.synopsis.isNotEmpty()) {
                item {
                    SectionTitle("القصة")
                    GlassPanel(Modifier.fillMaxWidth().clickable { synopsisOpen = !synopsisOpen }, padding = 12.dp) {
                        val shown = if (synopsisOpen) d.synopsis else d.synopsis.take(2)
                        shown.forEach {
                            Text(
                                it, color = colors.text, fontSize = 14.sp, lineHeight = 24.sp,
                                maxLines = if (synopsisOpen) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                        Text(if (synopsisOpen) "إخفاء" else "عرض المزيد", color = colors.accent, fontSize = 13.sp)
                    }
                }
            }
            item {
                val readCount = d.chapters.count { entry?.readChapters?.contains(it.url) == true }
                SectionTitle("الفصول (${d.chapters.size})") {
                    Text("قرأت $readCount", color = colors.muted, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    GlassChip(if (newestFirst) "الأحدث" else "الأقدم") { newestFirst = !newestFirst }
                    ChapterMenu(summary, d, onSelect = { selection = emptySet() })
                }
                Row(
                    Modifier.fillMaxWidth().glass(colors, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (filter.isEmpty()) Text("ابحث برقم أو عنوان الفصل...", color = colors.muted, fontSize = 14.sp)
                        BasicTextField(
                            filter, { filter = it }, singleLine = true,
                            textStyle = TextStyle(color = colors.text, fontSize = 14.sp, fontFamily = Cairo),
                            cursorBrush = SolidColor(colors.accent), modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (filter.isNotEmpty()) {
                        Icon(Icons.Filled.Close, null, tint = colors.muted, modifier = Modifier.size(18.dp).clickable { filter = "" })
                    }
                }
                Text(
                    "اضغط مطولًا على فصل لتحديد عدة فصول وتنزيلها.",
                    color = colors.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }
            val ordered = if (newestFirst) d.chapters.asReversed() else d.chapters
            val shown = if (filter.isBlank()) ordered else ordered.filter {
                it.number.contains(filter.trim()) || it.title?.contains(filter.trim()) == true
            }
            items(shown, key = { it.url }) { ch ->
                val selected = selection?.contains(ch.url)
                ChapterRow(
                    ch,
                    read = entry?.readChapters?.contains(ch.url) == true,
                    current = entry?.lastChapterUrl == ch.url,
                    selected = selected,
                    onClick = {
                        val sel = selection
                        if (sel != null) selection = if (ch.url in sel) sel - ch.url else sel + ch.url
                        else services.nav.go(Screen.Reader(summary, ch.url, if (entry?.lastChapterUrl == ch.url) entry.lastParagraph else 0))
                    },
                    onLongClick = { selection = (selection ?: emptySet()) + ch.url },
                    onDownload = {
                        services.askNotifications()
                        Downloads.enqueue(summary, listOf(ch), d)
                    },
                )
            }
        }
        val sel = selection
        if (sel != null && d != null) {
            SelectionBar(
                count = sel.size,
                modifier = Modifier.align(Alignment.BottomCenter),
                onAll = {
                    val visible = (if (filter.isBlank()) d.chapters else d.chapters.filter {
                        it.number.contains(filter.trim()) || it.title?.contains(filter.trim()) == true
                    }).map { it.url }
                    selection = if (sel.containsAll(visible)) emptySet() else sel + visible
                },
                onDownload = {
                    services.askNotifications()
                    Downloads.enqueue(summary, d.chapters.filter { it.url in sel }, d)
                    selection = null
                },
                onRead = {
                    LibraryStore.edit(summary.url, summary.title, summary.cover) { it }
                    LibraryStore.markRead(summary.url, sel, true)
                    selection = null
                },
                onClose = { selection = null },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(
    ch: ChapterRef,
    read: Boolean,
    current: Boolean,
    selected: Boolean?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
) {
    val colors = LocalAppColors.current
    @Suppress("UNUSED_VARIABLE") val version = ChapterStore.version
    val saved = ChapterStore.has(ch.url)
    val queued = !saved && Downloads.isQueued(ch.url)
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .then(
                if (selected == true) Modifier.clip(shape).background(colors.accent.copy(alpha = 0.28f))
                else Modifier.glass(colors, shape, strong = current)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Filled.Add, null,
                tint = if (selected) colors.accent else colors.muted, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
        } else if (current) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(colors.accent))
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                ch.number, color = if (read) colors.muted else colors.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(ch.title?.takeIf { it != ch.number }, ch.date).joinToString("  •  ")
            if (sub.isNotEmpty()) {
                Text(sub, color = colors.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (read) {
            Icon(Icons.Filled.Done, "مقروء", tint = colors.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            when {
                saved -> Icon(KolIcons.Offline, "محفوظ على الجهاز", tint = colors.accent, modifier = Modifier.size(20.dp))
                queued -> CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else -> Icon(
                    KolIcons.Download, "تنزيل الفصل", tint = colors.muted,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onDownload).padding(8.dp).size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    modifier: Modifier,
    onAll: () -> Unit,
    onDownload: () -> Unit,
    onRead: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        modifier.fillMaxWidth().padding(10.dp).glass(colors, RoundedCornerShape(18.dp), strong = true)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconBtn(Icons.Filled.Close, "إلغاء التحديد", onClick = onClose)
        Text("$count محدد", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        GlassChip("الكل", onClick = onAll)
        GlassChip("مقروء", onClick = onRead)
        AccentButton("تنزيل", KolIcons.Download, onDownload, enabled = count > 0)
    }
}

/** Everything about saving this novel for offline reading, in one glass card. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadPanel(novel: NovelSummary, d: NovelDetails) {
    val services = LocalServices.current
    val colors = LocalAppColors.current
    val version = ChapterStore.version
    val saved = remember(version, d) { d.chapters.count { ChapterStore.has(it.url) } }
    val job = Downloads.jobs.firstOrNull { it.novelUrl == novel.url }
    val active = job != null && !job.finished
    var rangeOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var lastAdded by remember { mutableStateOf<String?>(null) }

    fun enqueue(chapters: List<ChapterRef>) {
        services.askNotifications()
        val n = Downloads.enqueue(novel, chapters, d)
        lastAdded = if (n == 0) "كل هذه الفصول محفوظة مسبقًا." else "أضيف $n فصل لقائمة التنزيل."
    }

    GlassPanel(Modifier.fillMaxWidth(), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(KolIcons.Download, null, tint = colors.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("تنزيل للقراءة بدون نت", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("محفوظ $saved من ${d.chapters.size} فصل", color = colors.muted, fontSize = 12.sp)
            }
            if (saved > 0 && !active) {
                IconBtn(Icons.Filled.Delete, "حذف الفصول المحفوظة") { confirmDelete = true }
            }
        }
        LinearProgressIndicator(
            progress = { if (d.chapters.isEmpty()) 0f else saved.toFloat() / d.chapters.size },
            color = colors.accent, trackColor = colors.glassFillStrong,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        if (active) {
            val handled = job!!.done + job.failed.size
            Text(
                if (Downloads.paused) "متوقف مؤقتًا عند $handled/${job.total}"
                else "يتم التنزيل: $handled/${job.total}" + (Downloads.current?.let { " • ${it.number}" } ?: ""),
                color = colors.text, fontSize = 13.sp,
            )
            Gap(6)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Downloads.paused) AccentButton("استئناف", Icons.Filled.PlayArrow, { Downloads.resume() })
                else GlassButton("إيقاف مؤقت", KolIcons.Pause, onClick = { Downloads.pause() })
                GlassButton("إلغاء", Icons.Filled.Close, onClick = { Downloads.cancel(novel.url) })
            }
            Gap(8)
        }
        val entry = LibraryStore.entries[novel.url]
        val unread = d.chapters.filter { entry?.readChapters?.contains(it.url) != true }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton("الرواية كاملة", KolIcons.Download, { enqueue(d.chapters) })
            GlassButton("غير المقروءة (${unread.size})", onClick = { enqueue(unread) })
            GlassButton("نطاق فصول...", onClick = { rangeOpen = true })
        }
        if (job != null && job.failed.isNotEmpty() && !active) {
            Gap(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("تعذر تنزيل ${job.failed.size} فصل.", color = colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                GlassButton("إعادة المحاولة", Icons.Filled.Refresh, onClick = { Downloads.retryFailed(novel.url) })
            }
        }
        lastAdded?.let {
            Gap(6)
            Text(it, color = colors.muted, fontSize = 12.sp)
        }
    }

    if (rangeOpen) {
        RangeDialog(d, entry = LibraryStore.entries[novel.url]?.lastChapterUrl, onDismiss = { rangeOpen = false }) { chosen ->
            rangeOpen = false
            enqueue(chosen)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("حذف الفصول المحفوظة؟") },
            text = { Text("سيتم حذف $saved فصل من هذه الرواية من الجهاز. يمكنك تنزيلها مرة أخرى لاحقًا.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    Downloads.cancel(novel.url)
                    ChapterStore.delete(d.chapters.map { it.url })
                }) { Text("حذف", color = colors.accent) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("إلغاء", color = colors.text) } },
            containerColor = colors.spec.backgroundAlt,
            titleContentColor = colors.text,
            textContentColor = colors.text,
        )
    }
}

/** Pick chapters by their position in the list (1 = first chapter), with quick "next N" choices. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RangeDialog(d: NovelDetails, entry: String?, onDismiss: () -> Unit, onConfirm: (List<ChapterRef>) -> Unit) {
    val colors = LocalAppColors.current
    val n = d.chapters.size
    // Start after the chapter being read, or at the first chapter not saved yet.
    val startIndex = remember(d) {
        val reading = d.chapters.indexOfFirst { it.url == entry }
        if (reading >= 0) reading + 1
        else d.chapters.indexOfFirst { !ChapterStore.has(it.url) }.coerceAtLeast(0)
    }.coerceAtMost(n - 1)
    var from by remember { mutableStateOf((startIndex + 1).toString()) }
    var to by remember { mutableStateOf((startIndex + 10).coerceAtMost(n).toString()) }
    val a = from.toIntOrNull()?.coerceIn(1, n)
    val b = to.toIntOrNull()?.coerceIn(1, n)
    val range = if (a != null && b != null) minOf(a, b)..maxOf(a, b) else null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.spec.backgroundAlt,
        titleContentColor = colors.text,
        textContentColor = colors.text,
        title = { Text("تنزيل نطاق من الفصول") },
        text = {
            Column {
                Text("الأرقام هنا ترتيب الفصل في القائمة (1 = أول فصل، $n = آخر فصل).", color = colors.muted, fontSize = 12.sp)
                Gap(10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberField("من", from, Modifier.weight(1f)) { from = it }
                    Spacer(Modifier.width(10.dp))
                    NumberField("إلى", to, Modifier.weight(1f)) { to = it }
                }
                Gap(8)
                if (range != null) {
                    Text("من: ${d.chapters[range.first - 1].label}", color = colors.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("إلى: ${d.chapters[range.last - 1].label}", color = colors.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Gap(10)
                Text("اختيار سريع (بعد الفصل الحالي):", color = colors.muted, fontSize = 12.sp)
                Gap(4)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(10, 25, 50, 100, 200).forEach { count ->
                        GlassChip("التالية $count") {
                            from = (startIndex + 1).toString()
                            to = (startIndex + count).coerceAtMost(n).toString()
                        }
                    }
                    GlassChip("حتى الآخر") {
                        from = (startIndex + 1).toString()
                        to = n.toString()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = range != null, onClick = {
                range?.let { r -> onConfirm(d.chapters.subList(r.first - 1, r.last)) }
            }) { Text(if (range != null) "تنزيل ${range.last - range.first + 1} فصل" else "تنزيل", color = colors.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء", color = colors.text) } },
    )
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    val colors = LocalAppColors.current
    Column(modifier) {
        Text(label, color = colors.muted, fontSize = 12.sp)
        Box(Modifier.fillMaxWidth().glass(colors, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
            BasicTextField(
                value, { v -> onChange(v.filter { it.isDigit() }.take(6)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = TextStyle(color = colors.text, fontSize = 16.sp, fontFamily = Cairo),
                cursorBrush = SolidColor(colors.accent), modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ChapterMenu(novel: NovelSummary, d: NovelDetails, onSelect: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconBtn(Icons.Filled.MoreVert, "خيارات") { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem({ Text("تحديد فصول...") }, onClick = {
                open = false
                onSelect()
            })
            DropdownMenuItem({ Text("تحديد الكل كمقروء") }, onClick = {
                open = false
                LibraryStore.edit(novel.url, novel.title, novel.cover) { it }
                LibraryStore.markRead(novel.url, d.chapters.map { it.url }, true)
            })
            DropdownMenuItem({ Text("تحديد الكل كغير مقروء") }, onClick = {
                open = false
                LibraryStore.markRead(novel.url, d.chapters.map { it.url }, false)
            })
            val last = LibraryStore.entries[novel.url]?.lastChapterUrl
            val idx = d.chapters.indexOfFirst { it.url == last }
            if (idx > 0) {
                DropdownMenuItem({ Text("تحديد ما قبل الفصل الحالي كمقروء") }, onClick = {
                    open = false
                    LibraryStore.markRead(novel.url, d.chapters.take(idx).map { it.url }, true)
                })
            }
        }
    }
}
