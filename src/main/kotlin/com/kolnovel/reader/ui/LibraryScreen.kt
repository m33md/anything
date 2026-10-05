package com.kolnovel.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kolnovel.reader.data.LibraryEntry
import com.kolnovel.reader.data.LibraryStore
import com.kolnovel.reader.data.NovelSummary
import com.kolnovel.reader.data.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class LibTab(val label: String) { Library("المكتبة"), Favorites("المفضلة"), History("السجل") }

@Composable
fun LibraryScreen() {
    val services = LocalServices.current
    val colors = LocalAppColors.current
    var tab by remember { mutableStateOf(LibTab.Library) }
    var checking by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val all = LibraryStore.entries.values
    val items: List<LibraryEntry> = when (tab) {
        LibTab.Library -> all.filter { it.inLibrary }.sortedWith(compareByDescending<LibraryEntry> { it.newChapters > 0 }.thenByDescending { maxOf(it.lastReadAt, it.addedAt) })
        LibTab.Favorites -> all.filter { it.favorite }.sortedByDescending { it.lastReadAt }
        LibTab.History -> all.filter { it.lastReadAt > 0 && it.lastChapterUrl != null }.sortedByDescending { it.lastReadAt }
    }
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
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    LibTab.entries.forEach { t ->
                        GlassChip(t.label, t == tab) { tab = t }
                        Spacer(Modifier.width(6.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    if (checking != null) {
                        Text(checking!!, color = colors.muted, fontSize = 13.sp)
                    } else if (tab == LibTab.Library && items.isNotEmpty()) {
                        GlassButton("بحث عن فصول جديدة", Icons.Filled.Refresh, onClick = {
                            scope.launch {
                                val list = items
                                list.forEachIndexed { i, e ->
                                    checking = "يفحص ${i + 1}/${list.size}: ${e.title}"
                                    runCatching { services.source.details(e.url) }.getOrNull()?.let {
                                        LibraryStore.noteChapterCount(e.url, it.chapters.size)
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
                        when (tab) {
                            LibTab.Library -> "مكتبتك فارغة. افتح أي رواية واضغط \"أضف لمكتبتي\"."
                            LibTab.Favorites -> "لا توجد روايات مفضلة بعد."
                            LibTab.History -> "لم تقرأ أي فصل بعد."
                        },
                        color = colors.muted, fontSize = 15.sp, modifier = Modifier.padding(30.dp),
                    )
                }
            }
            items(items, key = { it.url }) { e ->
                val novel = NovelSummary(e.url, e.title, e.cover)
                NovelCard(
                    novel,
                    onClick = {
                        if (tab == LibTab.History && e.lastChapterUrl != null) services.nav.go(Screen.Reader(novel, e.lastChapterUrl, e.lastParagraph))
                        else services.nav.go(Screen.Details(novel))
                    },
                    subtitle = e.lastChapterTitle ?: "لم تبدأ بعد",
                )
            }
        }
        EdgeScrollbar(rememberScrollbarAdapter(gridState))
    }
}
