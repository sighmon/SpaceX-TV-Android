package com.sighmon.spacextv.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.view.MotionEvent
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sighmon.spacextv.data.Broadcast
import com.sighmon.spacextv.data.ContentKind
import com.sighmon.spacextv.data.GalleryImage
import com.sighmon.spacextv.data.MediaKind
import com.sighmon.spacextv.data.NextLaunch
import com.sighmon.spacextv.data.PostMediaItem
import com.sighmon.spacextv.data.SourceKind
import com.sighmon.spacextv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SpaceBlack = Color(0xFF05070A)
private val Panel = Color(0xFF111722)
private val PanelFocused = Color(0xFF163E6D)
private val SelectedPanel = Color(0xFF245EA8)
private val FocusedButtonPanel = Color(0xFF2B3F57)
private val Line = Color(0xFF35465F)
private val FocusOutline = Color.White
private val SelectedAccent = Color(0xFF78B7FF)
private val SoftText = Color(0xFFB9C4D4)

@Composable
fun SpaceXTvApp(model: SpaceXTvViewModel) {
    val state by model.state.collectAsState()
    var showsSettings by remember { mutableStateOf(false) }
    val galleryBroadcast = state.galleryBroadcast
    val selectedBroadcast = state.selectedBroadcast
    val collectionBroadcast = state.collectionBroadcast

    Box(Modifier.fillMaxSize().background(SpaceBlack)) {
        when {
            galleryBroadcast != null -> GalleryScreen(galleryBroadcast, model::closeGallery)
            collectionBroadcast != null -> CollectionScreen(
                collection = collectionBroadcast,
                onBack = model::closeCollection,
                onVideo = model::playCollectionVideo,
                onPhotos = model::openCollectionPhotos,
            )
            selectedBroadcast != null -> PlayerScreen(state, model::closePlayer)
            showsSettings -> SettingsScreen(
                state = state,
                onTokenChange = model::setToken,
                onUseTokenChange = model::setUsesXApiBearerToken,
                onDebugOverlayChange = model::setShowsPlayerDebugOverlay,
                onCountdownChange = model::setShowsNextLaunchCountdown,
                onCardFiltersChange = model::setShowsCardFilters,
                onLogosChange = model::setShowsSpaceXLogos,
                onMp4PreferenceChange = model::setPrefersMp4Playback,
                onClearCaches = model::clearCaches,
                onRefresh = {
                    showsSettings = false
                    model.refresh()
                },
                onBack = { showsSettings = false },
            )
            else -> BrowserScreen(
                state = state,
                onSettings = { showsSettings = true },
                onRefresh = model::refresh,
                onLoadMore = model::loadMore,
                onLogoToggle = { model.setShowsSpaceXLogos(!state.showsSpaceXLogos) },
                onPlay = model::play,
            )
        }
    }
}

@Composable
private fun BrowserScreen(
    state: SpaceXTvState,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onLogoToggle: () -> Unit,
    onPlay: (Broadcast) -> Unit,
) {
    var filter by remember { mutableStateOf(CardFilter.ALL) }
    val settingsFocusRequester = remember { FocusRequester() }
    val filterFocusRequesters = remember { CardFilter.entries.map { FocusRequester() } }
    val homeScrollState = rememberScrollState()
    LaunchedEffect(state.loadingState) {
        if (state.loadingState is LoadingState.Loaded) {
            homeScrollState.scrollTo(0)
            filterFocusRequesters.getOrNull(filter.ordinal)?.requestFocus()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val isTv = isTelevisionUi()
        val compact = maxWidth < 600.dp
        val horizontalPadding = if (compact) 18.dp else 48.dp
        val topPadding = if (compact) 22.dp else 34.dp
        val homeBrandSize = when {
            isTv -> 80
            compact -> 42
            else -> 52
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = horizontalPadding, top = topPadding, end = horizontalPadding)
                .verticalScroll(homeScrollState),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BrandIcon(showSpaceX = state.showsSpaceXLogos, sizeDp = homeBrandSize)
                Spacer(Modifier.weight(1f))
                HeaderIconButton(
                    R.drawable.ic_settings,
                    "Settings",
                    onSettings,
                    focusRequester = settingsFocusRequester,
                )
                Spacer(Modifier.width(12.dp))
                HeaderIconButton(R.drawable.ic_refresh, "Refresh", onRefresh)
            }
            if (state.showsNextLaunchCountdown) {
                Spacer(Modifier.height(20.dp))
                LaunchStrip(state.nextLaunch, state.nextLaunchError)
            }
            Spacer(Modifier.height(if (compact) 18.dp else 28.dp))
            if (state.showsCardFilters) {
                FilterBar(
                    selected = filter,
                    onFilter = { filter = it },
                    filterFocusRequesters = filterFocusRequesters,
                    upFocusRequester = settingsFocusRequester,
                )
                Spacer(Modifier.height(18.dp))
            }
            when (val loading = state.loadingState) {
                LoadingState.Idle, LoadingState.Loading -> Box(Modifier.fillMaxWidth().height(360.dp), contentAlignment = Alignment.Center) {
                    BasicText("Loading SpaceX broadcasts...", style = TextStyle(Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold))
                }
                is LoadingState.Failed -> MissingTokenOrError(loading.message, onSettings)
                LoadingState.Loaded -> BroadcastGrid(
                    state = state,
                    filter = filter,
                    firstRowUpRequester = filterFocusRequesters.getOrNull(filter.ordinal),
                    onPlay = onPlay,
                    onLoadMore = onLoadMore,
                    onLogoToggle = onLogoToggle,
                )
            }
        }
    }
}

@Composable
private fun BroadcastGrid(
    state: SpaceXTvState,
    filter: CardFilter,
    firstRowUpRequester: FocusRequester?,
    onPlay: (Broadcast) -> Unit,
    onLoadMore: () -> Unit,
    onLogoToggle: () -> Unit,
) {
    val activeFilter = if (state.showsCardFilters) filter else CardFilter.ALL
    val xBroadcasts = state.broadcasts.filter { it.sourceKind == SourceKind.X_BROADCAST && !it.isStarshipFlightTest }.takeIf { activeFilter == CardFilter.ALL || activeFilter == CardFilter.BROADCASTS }.orEmpty()
    val films = state.broadcasts.filter { it.sourceKind == SourceKind.HLS && it.subtitle.startsWith("Starship film", ignoreCase = true) }.takeIf { activeFilter == CardFilter.ALL || activeFilter == CardFilter.FILMS }.orEmpty()
    val tests = state.broadcasts.filter { it.isStarshipFlightTest }.takeIf { activeFilter == CardFilter.ALL || activeFilter == CardFilter.FLIGHT_TESTS }.orEmpty()
    val talks = state.broadcasts.filter { it.sourceKind == SourceKind.HLS && it.subtitle.startsWith("Starship talk", ignoreCase = true) }.takeIf { activeFilter == CardFilter.ALL || activeFilter == CardFilter.TALKS }.orEmpty()

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 600.dp
        val cellMinWidth = if (compact) 160.dp else 240.dp
        val spacing = if (compact) 14.dp else 22.dp
        val verticalSpacing = if (compact) 16.dp else 24.dp
        val rowHeight = if (compact) 190.dp else 230.dp
        val columns = ((maxWidth + spacing) / (cellMinWidth + spacing)).toInt().coerceAtLeast(1)
        val xRows = remember(xBroadcasts, columns) { xBroadcasts.chunked(columns) }
        val filmRows = remember(films, columns) { films.chunked(columns) }
        val testRows = remember(tests, columns) { tests.chunked(columns) }
        val talkRows = remember(talks, columns) { talks.chunked(columns) }
        val allRows = remember(xRows, filmRows, testRows, talkRows) { xRows + filmRows + testRows + talkRows }
        val rowFocusRequesters = remember(allRows.map { row -> row.map { it.id } }, columns) {
            allRows.map { row -> List(row.size) { FocusRequester() } }
        }
        var rowOffset = 0
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        if (xBroadcasts.isNotEmpty()) {
            SectionGrid(null, xRows, rowFocusRequesters, rowOffset, columns, spacing, verticalSpacing, rowHeight, firstRowUpRequester, onPlay)
            rowOffset += xRows.size
        }
        if (state.canLoadMore && (activeFilter == CardFilter.ALL || activeFilter == CardFilter.BROADCASTS)) {
            LoadMoreTile(state.isLoadingMore, onLoadMore)
        }
        if (films.isNotEmpty()) {
            SectionGrid("STARSHIP FILMS", filmRows, rowFocusRequesters, rowOffset, columns, spacing, verticalSpacing, rowHeight, firstRowUpRequester, onPlay)
            rowOffset += filmRows.size
        }
        if (tests.isNotEmpty()) {
            SectionGrid("STARSHIP TEST FLIGHTS", testRows, rowFocusRequesters, rowOffset, columns, spacing, verticalSpacing, rowHeight, firstRowUpRequester, onPlay)
            rowOffset += testRows.size
        }
        if (talks.isNotEmpty()) {
            SectionGrid("STARSHIP TALKS", talkRows, rowFocusRequesters, rowOffset, columns, spacing, verticalSpacing, rowHeight, firstRowUpRequester, onPlay)
        }
        if (xBroadcasts.isEmpty() && films.isEmpty() && tests.isEmpty() && talks.isEmpty()) Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
            BasicText("No matching cards", style = TextStyle(Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold))
        }
        if (state.broadcasts.isNotEmpty()) HomeFooter(state, onLogoToggle)
    }
    }
}

@Composable
private fun SectionGrid(
    title: String?,
    rows: List<List<Broadcast>>,
    rowFocusRequesters: List<List<FocusRequester>>,
    startRowIndex: Int,
    columns: Int,
    horizontalSpacing: Dp,
    verticalSpacing: Dp,
    rowHeight: Dp,
    firstRowUpRequester: FocusRequester?,
    onPlay: (Broadcast) -> Unit,
) {
    Column {
        if (title != null) {
            BasicText(title, style = TextStyle(SoftText, fontSize = 18.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(12.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(verticalSpacing)) {
            rows.forEachIndexed { localRowIndex, row ->
                val globalRowIndex = startRowIndex + localRowIndex
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(horizontalSpacing)) {
                    row.forEachIndexed { columnIndex, broadcast ->
                        val upRequester = rowFocusRequesters.getOrNull(globalRowIndex - 1)?.let { previousRow ->
                            previousRow.getOrNull(columnIndex) ?: previousRow.lastOrNull()
                        }
                        val downRequester = rowFocusRequesters.getOrNull(globalRowIndex + 1)?.let { nextRow ->
                            nextRow.getOrNull(columnIndex) ?: nextRow.lastOrNull()
                        }
                        BroadcastTile(
                            broadcast = broadcast,
                            onClick = { onPlay(broadcast) },
                            modifier = Modifier
                                .focusRequester(rowFocusRequesters[globalRowIndex][columnIndex])
                                .focusProperties {
                                    if (upRequester != null) {
                                        up = upRequester
                                    } else if (firstRowUpRequester != null) {
                                        up = firstRowUpRequester
                                    }
                                    if (downRequester != null) down = downRequester
                                }
                                .weight(1f)
                                .height(rowHeight),
                        )
                    }
                    repeat(columns - row.size) {
                        Spacer(Modifier.weight(1f).height(rowHeight))
                    }
                }
            }
        }
    }
}

@Composable
private fun BroadcastTile(broadcast: Broadcast, onClick: () -> Unit, titleOverride: String? = null, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) PanelFocused else Panel)
            .border(if (focused) 4.dp else 1.dp, if (focused) FocusOutline else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(if (focused) 8.dp else 10.dp)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Color(0xFF19202B))) {
            RemoteImage(broadcast.thumbnailUrl, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).height(70.dp))
            CardActionIcon(broadcast, Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
        Spacer(Modifier.height(10.dp))
        BasicText(
            titleOverride ?: broadcast.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            listOfNotNull(statusLabel(broadcast), broadcast.mediaSummaryLabel, broadcast.subtitle).joinToString(" - "),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(SoftText, fontSize = 12.sp),
        )
    }
}

private fun statusLabel(broadcast: Broadcast): String? = when {
    broadcast.isPinned -> "Pinned"
    broadcast.isLive == true -> "Live"
    else -> null
}

@Composable
private fun CardActionIcon(broadcast: Broadcast, modifier: Modifier = Modifier) {
    val icon = when {
        broadcast.isUpcoming -> R.drawable.ic_schedule
        broadcast.contentKind == ContentKind.GALLERY -> R.drawable.ic_gallery
        broadcast.contentKind == ContentKind.COLLECTION -> R.drawable.ic_collection
        else -> R.drawable.ic_play
    }
    Box(
        modifier
            .size(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(Color.Black.copy(alpha = 0.58f)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            colorFilter = ColorFilter.tint(Color.White),
        )
    }
}

private enum class CardFilter(val title: String) {
    ALL("All"),
    BROADCASTS("Broadcasts"),
    FILMS("Films"),
    FLIGHT_TESTS("Flight Tests"),
    TALKS("Talks"),
}

@Composable
private fun FilterBar(
    selected: CardFilter,
    onFilter: (CardFilter) -> Unit,
    filterFocusRequesters: List<FocusRequester>,
    upFocusRequester: FocusRequester,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(CardFilter.entries) { filter ->
            TvButton(
                filter.title,
                onClick = { onFilter(filter) },
                modifier = Modifier
                    .focusRequester(filterFocusRequesters[filter.ordinal])
                    .focusProperties { up = upFocusRequester },
                selected = filter == selected,
            )
        }
    }
}

@Composable
private fun LoadMoreTile(isLoading: Boolean, onLoadMore: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Box(
        Modifier
            .aspectRatio(16f / 12f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) PanelFocused else Panel)
            .border(if (focused) 5.dp else 1.dp, if (focused) FocusOutline else Line, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interactionSource, indication = null, enabled = !isLoading, onClick = onLoadMore),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(if (isLoading) "Loading..." else "Load More", style = TextStyle(Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun LaunchStrip(nextLaunch: NextLaunch?, error: String?) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 760.dp
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            when {
                nextLaunch != null -> NextLaunchCountdown(nextLaunch, compact = compact)
                error != null -> Box(Modifier.widthIn(max = 900.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0D131D)).border(1.dp, Line, RoundedCornerShape(8.dp)).padding(16.dp)) {
                    BasicText("Next launch unavailable: $error", maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(SoftText, fontSize = 16.sp))
                }
                else -> Box(Modifier.widthIn(max = 900.dp).fillMaxWidth().height(72.dp), contentAlignment = Alignment.CenterEnd) {
                    BasicText("Loading next launch...", style = TextStyle(SoftText, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

@Composable
private fun NextLaunchCountdown(launch: NextLaunch, compact: Boolean) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(launch.launchDate) {
        while (true) {
            now = Instant.now()
            delay(1_000)
        }
    }
    val remaining = CountdownRemaining.between(now, launch.launchDate)
    val metadata = listOfNotNull(launch.vehicle, launch.launchSite).joinToString(" - ")
    val launchDate = displayDate.format(launch.launchDate)

    val summary: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            BasicText("NEXT LAUNCH", style = TextStyle(Color.White.copy(alpha = 0.58f), fontSize = 13.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(6.dp))
            BasicText(launch.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(Color.White, fontSize = if (compact) 22.sp else 30.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(5.dp))
            if (metadata.isNotEmpty()) {
                BasicText(metadata, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(SoftText, fontSize = 14.sp))
                Spacer(Modifier.height(3.dp))
            }
            BasicText(launchDate, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(SoftText, fontSize = 14.sp))
        }
    }
    val units: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.Bottom) {
            CountdownUnit(remaining.days, "DAYS", compact)
            CountdownSeparator(compact)
            CountdownUnit(remaining.hours, "HRS", compact)
            CountdownSeparator(compact)
            CountdownUnit(remaining.minutes, "MIN", compact)
            CountdownSeparator(compact)
            CountdownUnit(remaining.seconds, "SEC", compact, isLast = true)
        }
    }

    if (compact) {
        Column(Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.End) {
            summary()
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                units()
            }
        }
    } else {
        Row(Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { summary() }
            Box(contentAlignment = Alignment.CenterEnd) {
                units()
            }
        }
    }
}

@Composable
private fun CountdownUnit(value: Int, label: String, compact: Boolean, isLast: Boolean = false) {
    val width = when {
        isLast && compact -> 38.dp
        isLast -> 54.dp
        compact -> 50.dp
        else -> 74.dp
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(width)) {
        BasicText(
            "%02d".format(value),
            modifier = Modifier.fillMaxWidth(),
            style = TextStyle(Color.White, fontSize = if (compact) 26.sp else 34.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
        )
        Spacer(Modifier.height(5.dp))
        BasicText(
            label,
            modifier = Modifier.fillMaxWidth(),
            style = TextStyle(Color.White.copy(alpha = 0.54f), fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
        )
    }
}

@Composable
private fun CountdownSeparator(compact: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(if (compact) 8.dp else 12.dp)) {
        BasicText(":", style = TextStyle(Color.White.copy(alpha = 0.46f), fontSize = if (compact) 26.sp else 34.sp, fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(5.dp))
        BasicText(" ", style = TextStyle(Color.Transparent, fontSize = 11.sp))
    }
}

private data class CountdownRemaining(val days: Int, val hours: Int, val minutes: Int, val seconds: Int) {
    companion object {
        fun between(now: Instant, target: Instant): CountdownRemaining {
            val total = Duration.between(now, target).seconds.coerceAtLeast(0)
            val days = total / 86_400
            val hours = (total % 86_400) / 3_600
            val minutes = (total % 3_600) / 60
            val seconds = total % 60
            return CountdownRemaining(days.toInt(), hours.toInt(), minutes.toInt(), seconds.toInt())
        }
    }
}

@Composable
private fun SettingsScreen(
    state: SpaceXTvState,
    onTokenChange: (String) -> Unit,
    onUseTokenChange: (Boolean) -> Unit,
    onDebugOverlayChange: (Boolean) -> Unit,
    onCountdownChange: (Boolean) -> Unit,
    onCardFiltersChange: (Boolean) -> Unit,
    onLogosChange: (Boolean) -> Unit,
    onMp4PreferenceChange: (Boolean) -> Unit,
    onClearCaches: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.07f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText("Settings", style = TextStyle(Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.weight(1f))
                HeaderIconButton(R.drawable.ic_arrow_back, "Back", onBack)
                Spacer(Modifier.width(12.dp))
                HeaderIconButton(R.drawable.ic_save, "Save and refresh", onRefresh)
            }
            Spacer(Modifier.height(34.dp))
            BasicText("X API Bearer Token", style = TextStyle(SoftText, fontSize = 15.sp))
            Spacer(Modifier.height(8.dp))
            TokenField(state.xApiBearerToken, onTokenChange)
            Spacer(Modifier.height(26.dp))
            ToggleRow("Use your Bearer Token", state.usesXApiBearerToken, onUseTokenChange)
            Spacer(Modifier.height(14.dp))
            ToggleRow("Show Card Filters", state.showsCardFilters, onCardFiltersChange)
            Spacer(Modifier.height(14.dp))
            ToggleRow("Prefer MP4 Playback", state.prefersMp4Playback, onMp4PreferenceChange)
            Spacer(Modifier.height(14.dp))
            ToggleRow("Show SpaceX Logos", state.showsSpaceXLogos, onLogosChange)
            Spacer(Modifier.height(14.dp))
            ToggleRow("Player Debug Overlay", state.showsPlayerDebugOverlay, onDebugOverlayChange)
            Spacer(Modifier.height(14.dp))
            ToggleRow("Next Launch Countdown", state.showsNextLaunchCountdown, onCountdownChange)
            Spacer(Modifier.height(28.dp))
            Column(horizontalAlignment = Alignment.Start) {
                TvButton("Delete Caches", onClearCaches)
                Spacer(Modifier.height(12.dp))
                BasicText(cacheStatus(state), style = TextStyle(SoftText, fontSize = 13.sp))
            }
            Spacer(Modifier.height(28.dp))
            SettingsFooter(state)
        }
    }
}

private fun cacheStatus(state: SpaceXTvState): String =
    "X API cache: ${state.xApiCacheGeneratedAt ?: "Unavailable"}\nApp cache: ${state.appDataCacheCreatedAt ?: "Unavailable"}\nCard checks: ${state.cardCheckHits} hits, ${state.cardCheckMisses} misses"

@Composable
private fun SettingsFooter(state: SpaceXTvState) {
    val context = LocalContext.current
    val version = remember { appVersionText(context) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        AppIcon(64)
        BasicText("Made on Earth by humans", style = TextStyle(SoftText, fontSize = 16.sp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            BasicText("T+", style = TextStyle(Color.White.copy(alpha = 0.38f), fontSize = 16.sp, fontWeight = FontWeight.Bold))
            BasicText(formatViewingTime(state.totalViewingTimeSeconds), style = TextStyle(Color.White.copy(alpha = 0.68f), fontSize = 16.sp, fontWeight = FontWeight.Bold))
        }
        if (version.isNotEmpty()) {
            BasicText(version, style = TextStyle(SoftText.copy(alpha = 0.6f), fontSize = 16.sp))
        }
    }
}

@Composable
private fun HomeFooter(state: SpaceXTvState, onLogoToggle: () -> Unit) {
    var paranoidTapCount by remember { mutableStateOf(0) }
    val brandSize = if (isTelevisionUi()) 104 else 72
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        BrandIcon(showSpaceX = state.showsSpaceXLogos, sizeDp = brandSize)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.22f)))
        BasicText(
            "ONLY THE PARANOID SURVIVE",
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    paranoidTapCount += 1
                    if (paranoidTapCount >= 10) {
                        onLogoToggle()
                        paranoidTapCount = 0
                    }
                }
                .padding(top = 10.dp),
            style = TextStyle(Color.White.copy(alpha = 0.58f), fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
        )
        BasicText(
            cacheStatus(state),
            style = TextStyle(Color.White.copy(alpha = 0.46f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            BasicText("T+", style = TextStyle(Color.White.copy(alpha = 0.38f), fontSize = 13.sp, fontWeight = FontWeight.Bold))
            BasicText(formatViewingTime(state.totalViewingTimeSeconds), style = TextStyle(Color.White.copy(alpha = 0.68f), fontSize = 13.sp, fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun AppIcon(sizeDp: Int) {
    Image(
        painter = painterResource(R.drawable.app_icon),
        contentDescription = null,
        modifier = Modifier.size(sizeDp.dp).clip(RoundedCornerShape(8.dp)),
    )
}

@Composable
private fun BrandIcon(showSpaceX: Boolean, sizeDp: Int) {
    if (showSpaceX) {
        Image(
            painter = painterResource(R.drawable.ic_spacex),
            contentDescription = null,
            modifier = Modifier.width((sizeDp * 2.4f).dp).height(sizeDp.dp),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.72f)),
        )
    } else {
        AppIcon(sizeDp)
    }
}

@Composable
private fun isTelevisionUi(): Boolean {
    val uiMode = LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK
    return uiMode == Configuration.UI_MODE_TYPE_TELEVISION
}

private fun appVersionText(context: Context): String {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val versionName = info.versionName ?: ""
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    return when {
        versionName.isNotBlank() -> "Version $versionName ($versionCode)"
        versionCode > 0 -> "Build $versionCode"
        else -> ""
    }
}

@Composable
private fun TokenField(value: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(Color.White, fontSize = 16.sp),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Panel)
            .border(if (focused) 4.dp else 1.dp, if (focused) FocusOutline else Line, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(horizontal = 16.dp, vertical = 15.dp),
    )
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TvButton(if (value) "On" else "Off", onClick = { onChange(!value) })
        Spacer(Modifier.width(16.dp))
        BasicText(label, style = TextStyle(Color.White, fontSize = 18.sp))
    }
}

@Composable
private fun PlayerScreen(state: SpaceXTvState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val resolved = state.resolvedBroadcast
    var hasPlaybackStarted by remember(resolved?.streamUrl) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            resolved != null -> VideoPlayer(resolved.streamUrl, onPlaybackStarted = { hasPlaybackStarted = true })
            state.playerError != null -> CenterMessage(state.playerError)
            else -> CenterMessage("Resolving stream...")
        }
        if (state.showsPlayerDebugOverlay) {
            Column(Modifier.align(Alignment.TopStart).padding(24.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xAA000000)).padding(12.dp)) {
                BasicText(state.selectedBroadcast?.title ?: "Broadcast", style = TextStyle(Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                BasicText(resolved?.streamUrl ?: state.playerError ?: "Pending", style = TextStyle(SoftText, fontSize = 11.sp))
            }
        }
        if (!hasPlaybackStarted) {
            HeaderIconButton(R.drawable.ic_arrow_back, "Back", onBack, Modifier.align(Alignment.TopStart).padding(24.dp))
        }
    }
}

@Composable
private fun VideoPlayer(url: String, onPlaybackStarted: () -> Unit) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            VideoView(context).apply {
                val controller = MediaController(context).also { mediaController ->
                    mediaController.setAnchorView(this)
                }
                tag = url
                setVideoURI(Uri.parse(url))
                setMediaController(controller)
                setOnPreparedListener { player ->
                    player.isLooping = false
                    start()
                    onPlaybackStarted()
                    controller.hide()
                }
                setOnClickListener {
                    controller.show(3_000)
                }
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        controller.show(3_000)
                    }
                    false
                }
            }
        },
        update = { view ->
            if (view.tag != url) {
                view.tag = url
                view.setVideoURI(Uri.parse(url))
            }
        },
    )
}

@Composable
private fun GalleryScreen(broadcast: Broadcast, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        Column(Modifier.fillMaxSize().padding(if (compact) 18.dp else 48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(broadcast.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(Color.White, fontSize = if (compact) 22.sp else 28.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.width(20.dp))
            HeaderIconButton(R.drawable.ic_arrow_back, "Back", onBack)
        }
        Spacer(Modifier.height(26.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            items(broadcast.galleryImages) { image ->
                GalleryImageTile(image, compact)
            }
        }
        }
    }
}

@Composable
private fun CollectionScreen(
    collection: Broadcast,
    onBack: () -> Unit,
    onVideo: (PostMediaItem, Int, Int) -> Unit,
    onPhotos: (List<GalleryImage>) -> Unit,
) {
    BackHandler(onBack = onBack)
    val entries = remember(collection) { collectionEntries(collection) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        Column(Modifier.fillMaxSize().padding(if (compact) 18.dp else 48.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(collection.tweetText?.withoutLinks() ?: collection.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(Color.White, fontSize = if (compact) 22.sp else 28.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.width(20.dp))
                HeaderIconButton(R.drawable.ic_arrow_back, "Back", onBack)
            }
            Spacer(Modifier.height(26.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (compact) 160.dp else 280.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 22.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 24.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    when (entry) {
                        is CollectionEntry.Video -> BroadcastTile(
                            collection.videoBroadcast(entry.item, entry.index, entry.totalVideos),
                            onClick = { onVideo(entry.item, entry.index, entry.totalVideos) },
                            titleOverride = if (entry.totalVideos > 1) "Video ${entry.index} of ${entry.totalVideos}" else "Video",
                        )
                        is CollectionEntry.Photos -> BroadcastTile(
                            collection.photoGalleryBroadcast(entry.images),
                            onClick = { onPhotos(entry.images) },
                            titleOverride = if (entry.images.size == 1) "1 photo" else "${entry.images.size} photos",
                        )
                    }
                }
            }
        }
    }
}

private sealed interface CollectionEntry {
    val id: String
    data class Video(val item: PostMediaItem, val index: Int, val totalVideos: Int) : CollectionEntry {
        override val id: String = "video:${item.id}:$index"
    }
    data class Photos(val images: List<GalleryImage>) : CollectionEntry {
        override val id: String = "photos:${images.joinToString("|") { it.url }}"
    }
}

private fun collectionEntries(collection: Broadcast): List<CollectionEntry> {
    val result = mutableListOf<CollectionEntry>()
    val videos = collection.videoMediaItems
    val photoBuffer = mutableListOf<GalleryImage>()
    fun flushPhotos() {
        if (photoBuffer.isNotEmpty()) {
            result += CollectionEntry.Photos(photoBuffer.toList())
            photoBuffer.clear()
        }
    }
    var videoIndex = 0
    collection.mediaItems.forEach { item ->
        when (item.kind) {
            MediaKind.VIDEO -> {
                flushPhotos()
                videoIndex += 1
                result += CollectionEntry.Video(item, videoIndex, videos.size.coerceAtLeast(1))
            }
            MediaKind.PHOTO -> {
                val url = item.photoUrl ?: item.thumbnailUrl
                if (url != null) photoBuffer += GalleryImage(url, item.width, item.height, item.altText)
            }
        }
    }
    flushPhotos()
    if (result.isEmpty() && collection.galleryImages.isNotEmpty()) result += CollectionEntry.Photos(collection.galleryImages)
    return result
}

@Composable
private fun GalleryImageTile(image: GalleryImage, compact: Boolean) {
    Box(Modifier.width(if (compact) 300.dp else 620.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(8.dp)).background(Panel)) {
        RemoteImage(image.url, Modifier.fillMaxSize())
    }
}

@Composable
private fun MissingTokenOrError(message: String, onSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(message, style = TextStyle(Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(18.dp))
        TvButton("Open Settings", onSettings)
    }
}

@Composable
private fun CenterMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(message, style = TextStyle(Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold))
    }
}

private fun String.withoutLinks(): String = split(Regex("""\s+""")).filterNot { it.startsWith("http://") || it.startsWith("https://") }.joinToString(" ")

private fun formatViewingTime(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

@Composable
private fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val background = when {
        focused -> FocusedButtonPanel
        selected -> SelectedPanel
        else -> Color(0xFF1A2533)
    }
    val border = when {
        focused -> FocusOutline
        selected -> SelectedAccent
        else -> Line
    }
    val textColor = Color.White
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(if (focused) 4.dp else if (selected) 2.dp else 1.dp, border, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = TextStyle(textColor, fontSize = 15.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun HeaderIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    requestInitialFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val buttonFocusRequester = focusRequester ?: remember { FocusRequester() }
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) buttonFocusRequester.requestFocus()
    }
    Box(
        modifier
            .focusRequester(buttonFocusRequester)
            .size(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) FocusedButtonPanel else Color(0xFF1A2533))
            .border(if (focused) 4.dp else 1.dp, if (focused) FocusOutline else Line, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(Color.White),
        )
    }
}

@Composable
private fun RemoteImage(url: String?, modifier: Modifier) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = null
        if (!url.isNullOrBlank()) {
            bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
        }
    }
    val image = bitmap
    if (image != null) {
        Image(image.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(Color(0xFF151D28))) {
            BasicText("SPACEX", modifier = Modifier.align(Alignment.Center), style = TextStyle(Color(0xFF6F7F94), fontSize = 18.sp, fontWeight = FontWeight.Bold))
        }
    }
}

private val displayDate: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a").withZone(ZoneId.systemDefault())
