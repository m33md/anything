package com.kolnovel.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kolnovel.reader.data.ChapterLists
import com.kolnovel.reader.data.LibraryEntry
import com.kolnovel.reader.data.LibraryStore
import com.kolnovel.reader.data.NovelSummary
import com.kolnovel.reader.data.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One list: every novel you added, favourited or started reading, newest activity first,
 * each showing the last chapter read and how far into the novel that is.
 */
@Composable
fun LibraryScreen() {
    val services = LocalServices.current
    val colors = LocalAppColors.current
    var favouritesOnly by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val items: List<LibraryEntry> = LibraryStore.entries.values
        .filter { it.inLibrary || it.favorite || (it.lastReadAt > 0 && it.lastChapterUrl != null) }
        .filter { !favouritesOnly || it.favorite }
        .sortedWith(compareByDescending<LibraryEntry> { it.newChapters > 0 }.thenByDescending { maxOf(it.lastReadAt, it.addedAt) })
    val gridState = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(SettingsStore.settings.gridColumnsMin.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize().dragScroll(gridState),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionTitle("مكتبتي (${items.size})") {
                    GlassChip("المفضلة فقط", favouritesOnly) { favouritesOnly = !favouritesOnly }
                    Spacer(Modifier.width(8.dp))
                    if (checking != null) {
                        Text(checking!!, color = colors.muted, fontSize = 13.sp)
                    } else if (items.isNotEmpty()) {
                        GlassButton("بحث عن فصول جديدة", Icons.Filled.Refresh, onClick = {
                            scope.launch {
                                val list = items
                                list.forEachIndexed { i, e ->
                                    checking = "يفحص ${i + 1}/${list.size}: ${e.title}"
                                    runCatching { services.source.details(e.url) }.getOrNull()?.let {
                                        ChapterLists.remember(e.url, it.chapters)
                                        LibraryStore.noteChapters(e.url, it.chapters)
                                    }
                                    delay(300)
                                }
                                checking = null
                            }
                        })
                    }
                }
            }
            if (items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (favouritesOnly) "لا توجد روايات مفضلة بعد."
                        else "مكتبتك فارغة. افتح أي رواية واضغط \"أضف لمكتبتي\"، أو ابدأ قراءتها وستظهر هنا.",
                        color = colors.muted, fontSize = 15.sp, modifier = Modifier.padding(30.dp),
                    )
                }
            }
            items(items, key = { it.url }) { e ->
                val novel = NovelSummary(e.url, e.title, e.cover)
                NovelCard(
                    novel,
                    onClick = { services.nav.go(Screen.Details(novel)) },
                    subtitle = e.lastChapterTitle ?: "لم تبدأ بعد",
                    footer = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { ReadingProgress(e) }
                            Icon(
                                Icons.Filled.Close, "إزالة من مكتبتي", tint = colors.muted,
                                modifier = Modifier.padding(start = 6.dp).clip(CircleShape).pointerHoverIcon(PointerIcon.Hand)
                                    .clickable { LibraryStore.remove(e.url) }.padding(3.dp).size(16.dp),
                            )
                        }
                    },
                )
            }
        }
        EdgeScrollbar(rememberScrollbarAdapter(gridState))
    }
}
