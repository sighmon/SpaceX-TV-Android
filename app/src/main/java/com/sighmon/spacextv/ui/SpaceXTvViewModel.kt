package com.sighmon.spacextv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sighmon.spacextv.data.Broadcast
import com.sighmon.spacextv.data.BroadcastDiscovery
import com.sighmon.spacextv.data.BroadcastResolver
import com.sighmon.spacextv.data.ContentKind
import com.sighmon.spacextv.data.DiscoveryResult
import com.sighmon.spacextv.data.GalleryImage
import com.sighmon.spacextv.data.LaunchScheduleService
import com.sighmon.spacextv.data.MediaKind
import com.sighmon.spacextv.data.NextLaunch
import com.sighmon.spacextv.data.PostMediaItem
import com.sighmon.spacextv.data.ResolvedBroadcast
import com.sighmon.spacextv.data.SettingsStore
import com.sighmon.spacextv.data.SourceKind
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SpaceXTvState(
    val broadcasts: List<Broadcast> = emptyList(),
    val loadingState: LoadingState = LoadingState.Idle,
    val isLoadingMore: Boolean = false,
    val debugLines: List<String> = emptyList(),
    val appDataCacheCreatedAt: Instant? = null,
    val xApiCacheGeneratedAt: Instant? = null,
    val cardCheckHits: Int = 0,
    val cardCheckMisses: Int = 0,
    val totalViewingTimeSeconds: Long = 0,
    val xApiBearerToken: String = "",
    val usesXApiBearerToken: Boolean = false,
    val showsPlayerDebugOverlay: Boolean = false,
    val showsNextLaunchCountdown: Boolean = true,
    val showsCardFilters: Boolean = true,
    val showsSpaceXLogos: Boolean = false,
    val prefersMp4Playback: Boolean = false,
    val nextLaunch: NextLaunch? = null,
    val nextLaunchError: String? = null,
    val selectedBroadcast: Broadcast? = null,
    val resolvedBroadcast: ResolvedBroadcast? = null,
    val playerError: String? = null,
    val galleryBroadcast: Broadcast? = null,
    val collectionBroadcast: Broadcast? = null,
    val requestedLimit: Int = 0,
) {
    val hasToken: Boolean get() = xApiBearerToken.trim().isNotEmpty()
    val canLoadMore: Boolean get() = usesXApiBearerToken && hasToken && requestedLimit < 20
    val hasLiveBroadcast: Boolean get() = broadcasts.any { it.isLive == true }
}

sealed interface LoadingState {
    data object Idle : LoadingState
    data object Loading : LoadingState
    data object Loaded : LoadingState
    data class Failed(val message: String) : LoadingState
}

class SpaceXTvViewModel(private val context: Context) : ViewModel() {
    private val settings = SettingsStore(context)
    private val discovery = BroadcastDiscovery()
    private val resolver = BroadcastResolver()
    private val launchSchedule = LaunchScheduleService()
    private val pageSize = 10
    private val cacheVersion = 29
    private var playbackStartedAt: Instant? = null
    private var lastNearLaunchRefreshDate: Instant? = null
    private var nearLaunchRefreshJob: Job? = null

    private val _state = MutableStateFlow(
        SpaceXTvState(
            xApiBearerToken = settings.xApiBearerToken,
            usesXApiBearerToken = settings.usesXApiBearerToken,
            showsPlayerDebugOverlay = settings.showsPlayerDebugOverlay,
            showsNextLaunchCountdown = settings.showsNextLaunchCountdown,
            showsCardFilters = settings.showsCardFilters,
            showsSpaceXLogos = settings.showsSpaceXLogos,
            prefersMp4Playback = settings.prefersMp4Playback,
            totalViewingTimeSeconds = settings.totalViewingTime,
        )
    )
    val state: StateFlow<SpaceXTvState> = _state.asStateFlow()

    init {
        load()
        loadNextLaunch()
    }

    fun load() {
        if (restoreDailyCache(pageSize)) return
        refresh()
    }

    fun refresh() {
        val current = _state.value
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loadingState = LoadingState.Loading,
                    isLoadingMore = false,
                    debugLines = listOf("Starting refresh"),
                    appDataCacheCreatedAt = null,
                    xApiCacheGeneratedAt = null,
                    cardCheckHits = 0,
                    cardCheckMisses = 0,
                )
            }
            runCatching { discover(pageSize, current) }
                .onSuccess { applySuccessfulDiscovery(it, pageSize) }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            broadcasts = emptyList(),
                            requestedLimit = 0,
                            debugLines = it.debugLines + (error.message ?: "Refresh failed"),
                            loadingState = LoadingState.Failed(error.message ?: "Refresh failed"),
                            appDataCacheCreatedAt = null,
                            xApiCacheGeneratedAt = null,
                            cardCheckHits = 0,
                            cardCheckMisses = 0,
                        )
                    }
                }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.isLoadingMore || !current.canLoadMore) return
        viewModelScope.launch {
            val target = (current.requestedLimit + pageSize).coerceAtMost(20)
            _state.update { it.copy(isLoadingMore = true) }
            runCatching { discover(target, current) }
                .onSuccess { applySuccessfulDiscovery(it, target, isLoadingMore = false) }
                .onFailure { error ->
                    _state.update { it.copy(isLoadingMore = false, debugLines = it.debugLines + "Load more failed: ${error.message}") }
                }
        }
    }

    fun setToken(token: String) {
        settings.xApiBearerToken = token
        _state.update { it.copy(xApiBearerToken = token) }
    }

    fun setUsesXApiBearerToken(value: Boolean) {
        settings.usesXApiBearerToken = value
        _state.update { it.copy(usesXApiBearerToken = value) }
        refresh()
    }

    fun setShowsPlayerDebugOverlay(value: Boolean) {
        settings.showsPlayerDebugOverlay = value
        _state.update { it.copy(showsPlayerDebugOverlay = value) }
    }

    fun setShowsNextLaunchCountdown(value: Boolean) {
        settings.showsNextLaunchCountdown = value
        if (!value) nearLaunchRefreshJob?.cancel()
        _state.update { it.copy(showsNextLaunchCountdown = value, nextLaunch = if (value) it.nextLaunch else null, nextLaunchError = if (value) it.nextLaunchError else null) }
        if (value && _state.value.nextLaunch == null) loadNextLaunch()
    }

    fun setShowsCardFilters(value: Boolean) {
        settings.showsCardFilters = value
        _state.update { it.copy(showsCardFilters = value) }
    }

    fun setShowsSpaceXLogos(value: Boolean) {
        settings.showsSpaceXLogos = value
        _state.update { it.copy(showsSpaceXLogos = value) }
    }

    fun setPrefersMp4Playback(value: Boolean) {
        settings.prefersMp4Playback = value
        _state.update { it.copy(prefersMp4Playback = value) }
        refresh()
    }

    fun clearCaches() {
        settings.clearCaches()
        _state.update {
            it.copy(
                appDataCacheCreatedAt = null,
                xApiCacheGeneratedAt = null,
                cardCheckHits = 0,
                cardCheckMisses = 0,
                debugLines = listOf("Caches deleted") + it.debugLines,
            )
        }
    }

    fun play(broadcast: Broadcast) {
        when (broadcast.contentKind) {
            ContentKind.GALLERY -> _state.update { it.copy(galleryBroadcast = broadcast) }
            ContentKind.COLLECTION -> _state.update { it.copy(collectionBroadcast = broadcast) }
            ContentKind.VIDEO -> {
                if (broadcast.sourceKind == SourceKind.YOUTUBE) {
                    openExternal(broadcast.sourceUrl)
                    return
                }
                viewModelScope.launch {
                    playbackStartedAt = Instant.now()
                    _state.update {
                        it.copy(
                            selectedBroadcast = broadcast,
                            resolvedBroadcast = null,
                            playerError = null,
                            galleryBroadcast = null,
                            collectionBroadcast = null,
                        )
                    }
                    runCatching { resolver.resolve(broadcast) }
                        .onSuccess { resolved -> _state.update { it.copy(resolvedBroadcast = resolved) } }
                        .onFailure { error -> _state.update { it.copy(playerError = error.message ?: "Unable to resolve this broadcast.") } }
                }
            }
        }
    }

    fun playCollectionVideo(item: PostMediaItem, index: Int, totalVideos: Int) {
        val collection = _state.value.collectionBroadcast ?: return
        play(collection.videoBroadcast(item, index, totalVideos))
    }

    fun openCollectionPhotos(images: List<GalleryImage>) {
        val collection = _state.value.collectionBroadcast ?: return
        _state.update { it.copy(galleryBroadcast = collection.photoGalleryBroadcast(images), collectionBroadcast = null) }
    }

    fun closePlayer() {
        recordPlaybackSession()
        _state.update { it.copy(selectedBroadcast = null, resolvedBroadcast = null, playerError = null) }
    }

    fun closeGallery() {
        _state.update { it.copy(galleryBroadcast = null) }
    }

    fun closeCollection() {
        _state.update { it.copy(collectionBroadcast = null) }
    }

    private suspend fun discover(limit: Int, state: SpaceXTvState): DiscoveryResult {
        if (state.usesXApiBearerToken && !state.hasToken) {
            throw IllegalStateException("Add an X API Bearer Token in Settings, or turn off personal-token discovery to use the shared cache.")
        }
        return discovery.discoverRecentSpaceXBroadcasts(
            limit = limit,
            xApiBearerToken = state.xApiBearerToken,
            usesXApiBearerToken = state.usesXApiBearerToken,
            prefersMp4Playback = state.prefersMp4Playback,
        )
    }

    private fun applySuccessfulDiscovery(result: DiscoveryResult, requestedLimit: Int, isLoadingMore: Boolean = false) {
        val createdAt = Instant.now()
        saveDailyCache(result, requestedLimit, createdAt)
        _state.update {
            it.copy(
                broadcasts = result.broadcasts,
                loadingState = LoadingState.Loaded,
                debugLines = result.debugLines,
                requestedLimit = requestedLimit,
                isLoadingMore = isLoadingMore,
                appDataCacheCreatedAt = createdAt,
                xApiCacheGeneratedAt = result.xApiCacheGeneratedAt,
                cardCheckHits = result.cardCheckHits,
                cardCheckMisses = result.cardCheckMisses,
            )
        }
        considerNearLaunchRefresh()
    }

    private fun loadNextLaunch() {
        if (!_state.value.showsNextLaunchCountdown) return
        viewModelScope.launch {
            val next = async { launchSchedule.nextLaunch() }
            runCatching { next.await() }
                .onSuccess { launch ->
                    _state.update { it.copy(nextLaunch = launch, nextLaunchError = null) }
                    watchNearLaunchWindow(launch.launchDate)
                }
                .onFailure { error -> _state.update { it.copy(nextLaunchError = error.message ?: "Unable to load next launch.") } }
        }
    }

    private fun watchNearLaunchWindow(launchDate: Instant) {
        nearLaunchRefreshJob?.cancel()
        nearLaunchRefreshJob = viewModelScope.launch {
            if (!_state.value.showsNextLaunchCountdown) return@launch
            val delayMillis = Duration.between(Instant.now(), launchDate.minusSeconds(5 * 60)).toMillis()
            if (delayMillis > 0) delay(delayMillis)
            if (!_state.value.showsNextLaunchCountdown) return@launch
            considerNearLaunchRefresh()
        }
    }

    private fun considerNearLaunchRefresh() {
        val current = _state.value
        val launchDate = current.nextLaunch?.launchDate ?: return
        val remaining = Duration.between(Instant.now(), launchDate)
        if (remaining.seconds !in 1..300) return
        if (lastNearLaunchRefreshDate == launchDate) return
        if (current.loadingState !is LoadingState.Loaded || current.isLoadingMore) return
        lastNearLaunchRefreshDate = launchDate
        viewModelScope.launch {
            val result = runCatching { discovery.discoverRecentSpaceXBroadcasts(current.requestedLimit.coerceAtLeast(pageSize), current.xApiBearerToken, current.usesXApiBearerToken, current.prefersMp4Playback) }
            result.onSuccess {
                _state.update { state -> state.copy(debugLines = listOf("Near-launch background refresh completed") + it.debugLines) }
                applySuccessfulDiscovery(it, current.requestedLimit.coerceAtLeast(pageSize))
            }.onFailure {
                lastNearLaunchRefreshDate = null
                _state.update { state -> state.copy(debugLines = listOf("Near-launch refresh failed: ${it.message}") + state.debugLines) }
            }
        }
    }

    private fun openExternal(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { error ->
            _state.update { it.copy(playerError = error.message ?: "No app can open this link.") }
        }
    }

    private fun recordPlaybackSession() {
        val started = playbackStartedAt ?: return
        playbackStartedAt = null
        val seconds = Duration.between(started, Instant.now()).seconds.coerceAtLeast(0)
        if (seconds == 0L) return
        val total = settings.totalViewingTime + seconds
        settings.totalViewingTime = total
        _state.update { it.copy(totalViewingTimeSeconds = total) }
    }

    private fun restoreDailyCache(minimumLimit: Int): Boolean {
        val cache = settings.cachedBroadcastsJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return false
        if (cache.optInt("version") != cacheVersion) return false
        if (cache.optInt("requestedLimit") < minimumLimit) return false
        val createdAt = Instant.ofEpochMilli(cache.optLong("createdAt", 0))
        if (createdAt.atZone(ZoneId.systemDefault()).toLocalDate() != LocalDate.now()) return false
        val broadcasts = cache.optJSONArray("broadcasts")?.toBroadcasts() ?: return false
        val debug = cache.optJSONArray("debugLines")?.toStringList() ?: emptyList()
        _state.update {
            it.copy(
                broadcasts = broadcasts,
                requestedLimit = cache.optInt("requestedLimit", minimumLimit),
                debugLines = listOf("Loaded ${broadcasts.size} broadcasts from today's cache") + debug,
                appDataCacheCreatedAt = createdAt,
                xApiCacheGeneratedAt = cache.optString("xApiCacheGeneratedAt").ifBlank { null }?.let(Instant::parse),
                cardCheckHits = cache.optInt("cardCheckHits"),
                cardCheckMisses = cache.optInt("cardCheckMisses"),
                loadingState = LoadingState.Loaded,
            )
        }
        return true
    }

    private fun saveDailyCache(result: DiscoveryResult, requestedLimit: Int, createdAt: Instant) {
        settings.cachedBroadcastsJson = JSONObject()
            .put("version", cacheVersion)
            .put("createdAt", createdAt.toEpochMilli())
            .put("requestedLimit", requestedLimit)
            .put("xApiCacheGeneratedAt", result.xApiCacheGeneratedAt?.toString())
            .put("cardCheckHits", result.cardCheckHits)
            .put("cardCheckMisses", result.cardCheckMisses)
            .put("broadcasts", result.broadcasts.broadcastsToJson())
            .put("debugLines", JSONArray(result.debugLines))
            .toString()
    }
}

class SpaceXTvViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = SpaceXTvViewModel(context) as T
}

private fun List<Broadcast>.broadcastsToJson(): JSONArray = JSONArray().also { array ->
    forEach { broadcast ->
        array.put(
            JSONObject()
                .put("id", broadcast.id)
                .put("title", broadcast.title)
                .put("subtitle", broadcast.subtitle)
                .put("sourceUrl", broadcast.sourceUrl)
                .put("sourceKind", broadcast.sourceKind.name)
                .put("contentKind", broadcast.contentKind.name)
                .put("streamUrl", broadcast.streamUrl)
                .put("fallbackStreamUrl", broadcast.fallbackStreamUrl)
                .put("tweetText", broadcast.tweetText)
                .put("publishedAt", broadcast.publishedAt?.toString())
                .put("thumbnailUrl", broadcast.thumbnailUrl)
                .put("galleryImages", broadcast.galleryImages.galleryImagesToJson())
                .put("mediaItems", broadcast.mediaItems.mediaItemsToJson())
                .put("isPinned", broadcast.isPinned)
                .put("isLive", broadcast.isLive)
                .put("isUpcoming", broadcast.isUpcoming)
        )
    }
}

private fun List<GalleryImage>.galleryImagesToJson(): JSONArray = JSONArray().also { images ->
    forEach { image -> images.put(JSONObject().put("url", image.url).put("width", image.width).put("height", image.height).put("altText", image.altText)) }
}

private fun List<PostMediaItem>.mediaItemsToJson(): JSONArray = JSONArray().also { items ->
    forEach { item ->
        items.put(
            JSONObject()
                .put("id", item.id)
                .put("kind", item.kind.name)
                .put("streamUrl", item.streamUrl)
                .put("thumbnailUrl", item.thumbnailUrl)
                .put("photoUrl", item.photoUrl)
                .put("width", item.width)
                .put("height", item.height)
                .put("altText", item.altText)
        )
    }
}

private fun JSONArray.toBroadcasts(): List<Broadcast> = (0 until length()).mapNotNull { index ->
    optJSONObject(index)?.let { objectValue ->
        Broadcast(
            id = objectValue.optString("id"),
            title = objectValue.optString("title"),
            subtitle = objectValue.optString("subtitle"),
            sourceUrl = objectValue.optString("sourceUrl"),
            sourceKind = enumValueOrDefault(objectValue.optString("sourceKind"), SourceKind.X_BROADCAST),
            contentKind = enumValueOrDefault(objectValue.optString("contentKind"), ContentKind.VIDEO),
            streamUrl = objectValue.optString("streamUrl").ifBlank { null },
            fallbackStreamUrl = objectValue.optString("fallbackStreamUrl").ifBlank { null },
            tweetText = objectValue.optString("tweetText").ifBlank { null },
            publishedAt = objectValue.optString("publishedAt").ifBlank { null }?.let { Instant.parse(it) },
            thumbnailUrl = objectValue.optString("thumbnailUrl").ifBlank { null },
            galleryImages = objectValue.optJSONArray("galleryImages")?.toGalleryImages() ?: emptyList(),
            mediaItems = objectValue.optJSONArray("mediaItems")?.toMediaItems() ?: emptyList(),
            isPinned = objectValue.optBoolean("isPinned"),
            isLive = if (objectValue.has("isLive") && !objectValue.isNull("isLive")) objectValue.optBoolean("isLive") else null,
            isUpcoming = objectValue.optBoolean("isUpcoming"),
        )
    }
}

private fun JSONArray.toGalleryImages(): List<GalleryImage> = (0 until length()).mapNotNull { index ->
    optJSONObject(index)?.let {
        GalleryImage(it.optString("url"), it.optInt("width").takeIf { value -> value > 0 }, it.optInt("height").takeIf { value -> value > 0 }, it.optString("altText").ifBlank { null })
    }
}

private fun JSONArray.toMediaItems(): List<PostMediaItem> = (0 until length()).mapNotNull { index ->
    optJSONObject(index)?.let {
        PostMediaItem(
            id = it.optString("id"),
            kind = enumValueOrDefault(it.optString("kind"), MediaKind.VIDEO),
            streamUrl = it.optString("streamUrl").ifBlank { null },
            thumbnailUrl = it.optString("thumbnailUrl").ifBlank { null },
            photoUrl = it.optString("photoUrl").ifBlank { null },
            width = it.optInt("width").takeIf { value -> value > 0 },
            height = it.optInt("height").takeIf { value -> value > 0 },
            altText = it.optString("altText").ifBlank { null },
        )
    }
}

private fun JSONArray.toStringList(): List<String> = (0 until length()).map { optString(it) }

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
    runCatching { enumValueOf<T>(value) }.getOrDefault(default)
