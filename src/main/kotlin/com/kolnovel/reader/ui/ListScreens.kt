package com.kolnovel.reader.ui

import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kolnovel.reader.data.BrowseFilter
import com.kolnovel.reader.data.FilterOptions
import com.kolnovel.reader.data.ListPage
import com.kolnovel.reader.data.NovelSummary
import com.kolnovel.reader.data.SettingsStore
import kotlinx.coroutines.launch

/** Loads page after page of novels as the user scrolls down. */
class PagedNovels(private val loader: suspend (page: Int) -> ListPage) {
    val items = mutableStateListOf<NovelSummary>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var endReached by mutableStateOf(false)
        private set
    private var nextPage = 1

    suspend fun loadMore() {
        if (loading || endReached) return
        loading = true
        error = null
        try {
            val page = loader(nextPage)
            val known = items.map { it.url }.toHashSet()
            items += page.items.filter { it.url !in known }
            nextPage++
            if (!page.hasNext || page.items.isEmpty()) endReached = true
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            loading = false
        }
    }
}

@Composable
fun NovelGrid(
    paged: PagedNovels,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    header: (@Composable () -> Unit)? = null,
    empty: String = "لا توجد نتائج.",
) {
    val services = LocalServices.current
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val minWidth = SettingsStore.settings.gridColumnsMin
    LaunchedEffect(paged) {
        if (paged.items.isEmpty()) paged.loadMore()
        snapshotFlow {
            val info = state.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 8
        }.collect { nearEnd -> if (nearEnd) paged.loadMore() }
    }
    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minWidth.dp),
            state = state,
            modifier = Modifier.fillMaxSize().dragScroll(state),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
            itemsIndexed(paged.items, key = { _, n -> n.url }) { _, novel ->
                NovelCard(novel, onClick = { services.nav.go(Screen.Details(novel)) })
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                when {
                    paged.error != null -> ErrorBox(paged.error!!, onRetry = { scope.launch { paged.loadMore() } })
                    paged.loading -> Loading()
                    paged.endReached && paged.items.isEmpty() ->
                        Text(empty, color = colors.muted, modifier = Modifier.padding(30.dp))
                }
            }
        }
        EdgeScrollbar(rememberScrollbarAdapter(state))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowseScreen() {
    val services = LocalServices.current
    val colors = LocalAppColors.current
    var filter by remember { mutableStateOf(BrowseFilter()) }
    var showGenres by remember { mutableStateOf(false) }
    val options by produceState(FilterOptions.Fallback) { value = services.source.filters() }
    val paged = remember(filter) { PagedNovels { page -> services.source.browse(filter, page) } }
    NovelGrid(paged, header = {
        GlassPanel(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("الترتيب:", color = colors.muted, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.orders.filter { it.value.isNotEmpty() }.forEach { o ->
                        GlassChip(o.label, filter.order == o.value) { filter = filter.copy(order = o.value) }
                    }
                }
            }
            Gap(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("الحالة:", color = colors.muted, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.statuses.forEach { o ->
                        GlassChip(o.label, filter.status == o.value) { filter = filter.copy(status = o.value) }
                    }
                }
            }
            if (options.types.isNotEmpty()) {
                Gap(8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("النوع:", color = colors.muted, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        options.types.forEach { o ->
                            val on = o.value in filter.types
                            GlassChip(o.label, on) {
                                filter = filter.copy(types = if (on) filter.types - o.value else filter.types + o.value)
                            }
                        }
                    }
                }
            }
            if (options.genres.isNotEmpty()) {
                Gap(8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassButton(
                        if (filter.genres.isEmpty()) "التصنيفات" else "التصنيفات (${filter.genres.size})",
                        if (showGenres) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        onClick = { showGenres = !showGenres },
                    )
                    if (filter.genres.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        GlassChip("مسح التصنيفات") { filter = filter.copy(genres = emptySet()) }
                    }
                }
                if (showGenres) {
                    Gap(8)
                    val genreScroll = rememberScrollState()
                    Box(Modifier.heightIn(max = 260.dp).verticalScroll(genreScroll).dragScroll(genreScroll)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            options.genres.forEach { o ->
                                val on = o.value in filter.genres
                                GlassChip(o.label, on) {
                                    filter = filter.copy(genres = if (on) filter.genres - o.value else filter.genres + o.value)
                                }
                            }
                        }
                    }
                }
            }
        }
    })
}

@Composable
fun SearchScreen(query: String) {
    val services = LocalServices.current
    val colors = LocalAppColors.current
    if (query.isBlank()) {
        Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("اكتب اسم الرواية في مربع البحث بالأعلى ثم اضغط Enter.", color = colors.muted, fontSize = 16.sp)
        }
        return
    }
    val paged = remember(query) { PagedNovels { page -> services.source.search(query, page) } }
    NovelGrid(paged, header = { SectionTitle("نتائج البحث عن \"$query\"") }, empty = "لم أجد روايات بهذا الاسم.")
}

@Composable
fun ListingScreen(title: String, url: String) {
    val services = LocalServices.current
    val paged = remember(url) { PagedNovels { page -> services.source.listing(url, page) } }
    NovelGrid(paged, header = { SectionTitle(title) })
}
