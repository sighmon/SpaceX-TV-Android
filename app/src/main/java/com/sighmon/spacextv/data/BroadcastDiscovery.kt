package com.sighmon.spacextv.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant

class BroadcastDiscovery(
    private val http: HttpClient = HttpClient(),
    private val resolver: BroadcastResolver = BroadcastResolver(http),
) {
    private val xCacheUrl = "https://www.sighmon.com/spacex-tv/x-cache.json"
    private val starshipPlaylistUrl = "https://content.spacex.com/api/spacex-website/media-playlist/starship"
    private val starshipFlightTestsPlaylistUrl = "https://content.spacex.com/api/spacex-website/media-playlist/starship-flight-tests"
    private val starshipTalksPlaylistUrl = "https://content.spacex.com/api/spacex-website/media-playlist/starship-talks"
    private val launchTilesUrl = "https://content.spacex.com/api/spacex-website/launches-page-tiles"
    private val missionsBaseUrl = "https://content.spacex.com/api/spacex-website/missions/"

    suspend fun discoverRecentSpaceXBroadcasts(
        limit: Int,
        xApiBearerToken: String?,
        usesXApiBearerToken: Boolean,
        prefersMp4Playback: Boolean,
    ): DiscoveryResult {
        val report = mutableListOf("Starting SpaceX post discovery")
        report += "SpaceX CMS playback preference: ${if (prefersMp4Playback) "MP4" else "HLS"}"
        val timelineLimit = (limit * 2).coerceAtLeast(25).coerceAtMost(100)
        val cacheSnapshot: JSONObject?
        val candidates = if (usesXApiBearerToken && !xApiBearerToken.isNullOrBlank()) {
            report += "X API timeline fetch limit: $timelineLimit"
            cacheSnapshot = null
            recentSpaceXBroadcastCandidatesFromApi(timelineLimit, xApiBearerToken.trim(), report)
        } else {
            report += "X API cache target timeline limit: $timelineLimit"
            cacheSnapshot = fetchXCache(report)
            recentSpaceXBroadcastCandidatesFromCache(cacheSnapshot, timelineLimit, report)
        }
        report += "Candidate statuses: ${candidates.size}"

        return discoveryResult(candidates, prefersMp4Playback, report, cacheSnapshot)
    }

    private suspend fun discoveryResult(
        initialCandidates: List<BroadcastCandidate>,
        prefersMp4Playback: Boolean,
        report: MutableList<String>,
        cacheSnapshot: JSONObject?,
    ): DiscoveryResult {
        val xCandidates = initialCandidates.deduplicatedX().sortedWith(compareByDescending<BroadcastCandidate> { it.isPinned }.thenByDescending { it.publishedAt })
        val films = runCatching { starshipMediaCandidates("film", prefersMp4Playback, report, cacheSnapshot?.optJSONObject("starship_playlist"), starshipPlaylistUrl) }
            .getOrElse { report += "Starship film discovery failed: ${it.message}"; emptyList() }
        report += "Starship film candidates: ${films.size}"
        val talks = runCatching { starshipMediaCandidates("talk", prefersMp4Playback, report, cacheSnapshot?.optJSONObject("starship_talks_playlist"), starshipTalksPlaylistUrl) }
            .getOrElse { report += "Starship talk discovery failed: ${it.message}"; emptyList() }
        report += "Starship talk candidates: ${talks.size}"
        val tests = runCatching { starshipFlightTestCandidates(prefersMp4Playback, report, cacheSnapshot) }
            .getOrElse { report += "Starship flight test discovery failed: ${it.message}"; emptyList() }
        report += "Starship flight test candidates: ${tests.size}"

        val selectedX = mutableListOf<Pair<BroadcastCandidate, Broadcast>>()
        xCandidates.filter { !it.isAppendedSpaceXContent }.take(80).forEachIndexed { index, candidate ->
            if (candidate.requiresMediaCollection) {
                val items = candidate.playableCollectionMediaItems
                report += "Adding collection ${index + 1}: ${candidate.statusUrl.substringAfterLast('/')}, ${mediaSummary(items) ?: "mixed media"}"
                selectedX += candidate to candidate.toBroadcast(contentKind = ContentKind.COLLECTION, mediaItems = items)
                return@forEachIndexed
            }

            if (candidate.galleryImages.isNotEmpty() && candidate.streamUrl == null && !candidate.allowsDeferredStreamResolution) {
                report += "Adding gallery ${index + 1}: ${candidate.galleryImages.size} photos"
                selectedX += candidate to candidate.toBroadcast(contentKind = ContentKind.GALLERY)
                return@forEachIndexed
            }

            if (candidate.streamUrl == null && !candidate.allowsDeferredStreamResolution) {
                report += "Skipping status without API media or broadcast link: ${candidate.statusUrl.substringAfterLast('/')}"
                return@forEachIndexed
            }

            val stream = candidate.streamUrl
            if (stream != null) {
                report += "Using X API media variant for ${candidate.statusUrl.substringAfterLast('/')}"
                selectedX += candidate to candidate.toBroadcast(streamUrl = stream)
            } else {
                report += "Probing ${index + 1}: ${candidate.statusUrl.substringAfterLast('/')}"
                runCatching { resolver.resolveStatusUrl(candidate.statusUrl) }
                    .onSuccess { resolved ->
                        report += "Found page stream"
                        selectedX += candidate to candidate.toBroadcast(
                            streamUrl = resolved.streamUrl,
                            thumbnailUrl = candidate.thumbnailUrl ?: resolved.thumbnailUrl,
                            isLive = resolved.isLive,
                        )
                    }
                    .onFailure { error ->
                        report += "Deferring stream resolution: ${error.message}"
                        selectedX += candidate to candidate.toBroadcast(isLive = false)
                    }
            }
        }

        val appended = (films.sortedByDescending { it.publishedAt } + tests.sortedByDescending { it.publishedAt } + talks.sortedByDescending { it.publishedAt })
            .map { it.toBroadcast() }
        val broadcasts = selectedX.sortedWith(compareBy<Pair<BroadcastCandidate, Broadcast>> { !it.first.isPinned }.thenByDescending { it.first.publishedAt })
            .map { it.second } + appended
        if (broadcasts.isEmpty()) throw IllegalStateException("No recent SpaceX statuses with broadcasts or image galleries were found.")
        report += "Adding ${appended.size} SpaceX Starship items after X posts"
        report += "Discovery complete: ${broadcasts.size} posts"
        return DiscoveryResult(
            broadcasts = broadcasts,
            debugLines = report,
            xApiCacheGeneratedAt = cacheSnapshot?.optString("generated_at").toInstantOrNull(),
            cardCheckHits = 0,
            cardCheckMisses = selectedX.size,
        )
    }

    private suspend fun fetchXCache(report: MutableList<String>): JSONObject {
        report += "Using X API cache for timeline discovery"
        report += "X API cache GET: $xCacheUrl"
        val cache = JSONObject(http.getString(xCacheUrl, userAgentHeaders()))
        report += "X API cache source: ${cache.optString("source", "unknown")}"
        cache.optString("generated_at").takeIf { it.isNotBlank() }?.let { report += "X API cache generated at: $it" }
        return cache
    }

    private fun recentSpaceXBroadcastCandidatesFromCache(cache: JSONObject, timelineLimit: Int, report: MutableList<String>): List<BroadcastCandidate> {
        val pinned = cache.optJSONObject("pinned")?.let(::parseTimeline)
        val timeline = parseTimeline(cache.getJSONObject("timeline")).copyPostsLimit(timelineLimit)
        report += "X API cache returned ${timeline.posts.length()} SpaceX posts"
        report += "X API cache included ${timeline.mediaByKey.size} media objects"
        return recentSpaceXBroadcastCandidates(pinned, timeline, report)
    }

    private suspend fun recentSpaceXBroadcastCandidatesFromApi(timelineLimit: Int, bearerToken: String, report: MutableList<String>): List<BroadcastCandidate> {
        report += "Using X API for timeline discovery"
        val user = JSONObject(xApiGet("https://api.x.com/2/users/by/username/spacex?user.fields=pinned_tweet_id", bearerToken, report)).getJSONObject("data")
        val pinned = user.optString("pinned_tweet_id").takeIf { it.isNotBlank() }?.let { id ->
            report += "X API pinned SpaceX post: $id"
            runCatching { parseTimeline(JSONObject(xApiGet("https://api.x.com/2/tweets?${tweetQuery(listOf("ids" to id))}", bearerToken, report))) }.getOrNull()
        }
        val timeline = parseTimeline(JSONObject(xApiGet("https://api.x.com/2/users/${user.getString("id")}/tweets?${tweetQuery(listOf("max_results" to timelineLimit.toString(), "exclude" to "replies"))}", bearerToken, report)))
        report += "X API returned ${timeline.posts.length()} SpaceX posts"
        report += "X API included ${timeline.mediaByKey.size} media objects"
        return recentSpaceXBroadcastCandidates(pinned, timeline, report)
    }

    private fun recentSpaceXBroadcastCandidates(pinned: XApiTimeline?, timeline: XApiTimeline, report: MutableList<String>): List<BroadcastCandidate> {
        val pinnedCandidates = pinned?.posts?.asObjects()?.mapNotNull { candidate(it, pinned, true, report) } ?: emptyList()
        val timelineCandidates = timeline.posts.asObjects().mapNotNull { candidate(it, timeline, false, report) }
        return (pinnedCandidates + timelineCandidates).deduplicatedX()
    }

    private fun candidate(post: JSONObject, timeline: XApiTimeline, isPinned: Boolean, report: MutableList<String>): BroadcastCandidate? {
        val postId = post.getString("id")
        val statusUrl = "https://x.com/spacex/status/$postId"
        val referenced = post.referencedContentTweetId()?.let { timeline.includedPostsById[it] }
        val linkedBroadcast = post.broadcastUrlFromEntities() ?: referenced?.broadcastUrlFromEntities()
        val sourceKind = if (linkedBroadcast?.isYouTubeUrl() == true) SourceKind.YOUTUBE else SourceKind.X_BROADCAST
        val ownMedia = mediaObjects(post, timeline.mediaByKey)
        val referencedMedia = referenced?.let { mediaObjects(it, timeline.mediaByKey) } ?: emptyList()
        val media = ownMedia.ifEmpty { referencedMedia }
        val mediaItems = postMediaItems(media)
        val galleryImages = mediaItems.filter { it.kind == MediaKind.PHOTO }.mapNotNull {
            val url = it.photoUrl ?: it.thumbnailUrl ?: return@mapNotNull null
            GalleryImage(url, it.width, it.height, it.altText)
        }
        val firstVideo = mediaItems.firstOrNull { it.kind == MediaKind.VIDEO && it.streamUrl != null }
        val thumbnail = media.firstNotNullOfOrNull { it.optString("preview_image_url").ifBlank { null } }
            ?: galleryImages.firstOrNull()?.url
            ?: post.thumbnailUrlFromEntities()
            ?: referenced?.thumbnailUrlFromEntities()
        val summary = mediaSummary(mediaItems)
        when {
            mediaItems.count { it.kind == MediaKind.VIDEO } > 1 || (mediaItems.any { it.kind == MediaKind.VIDEO } && mediaItems.any { it.kind == MediaKind.PHOTO }) ->
                report += "Multi-media collection for $postId: ${summary ?: "mixed media"}"
            firstVideo != null -> report += "API media variant for $postId: ${firstVideo.streamUrl}"
            linkedBroadcast != null -> report += "Broadcast link for $postId: $linkedBroadcast"
            galleryImages.isNotEmpty() -> report += "Image gallery for $postId: ${galleryImages.size} photos"
            else -> report += "No API media variant or broadcast link for $postId; will skip"
        }
        return BroadcastCandidate(
            statusUrl = linkedBroadcast ?: statusUrl,
            dedupeKey = dedupeKey(post, statusUrl, linkedBroadcast, mediaItems, firstVideo?.streamUrl, galleryImages, referenced?.optString("id")),
            streamUrl = firstVideo?.streamUrl,
            title = post.broadcastTitle(),
            subtitle = when {
                summary != null && (mediaItems.count { it.kind == MediaKind.VIDEO } > 1 || (mediaItems.any { it.kind == MediaKind.VIDEO } && mediaItems.any { it.kind == MediaKind.PHOTO })) -> "${if (isPinned) "Pinned " else ""}X media collection - $summary"
                firstVideo != null -> "${if (isPinned) "Pinned " else ""}X API media"
                linkedBroadcast != null -> "${if (isPinned) "Pinned " else ""}X broadcast link"
                else -> "${if (isPinned) "Pinned SpaceX status" else "X status"} $postId"
            },
            tweetText = post.optString("text").ifBlank { null },
            publishedAt = post.optString("created_at").toInstantOrNull(),
            thumbnailUrl = thumbnail,
            galleryImages = galleryImages,
            mediaItems = mediaItems,
            allowsDeferredStreamResolution = linkedBroadcast != null,
            isPinned = isPinned,
            sourceKind = sourceKind,
            originalPostId = postId,
        )
    }

    private suspend fun starshipMediaCandidates(kind: String, prefersMp4Playback: Boolean, report: MutableList<String>, cached: JSONObject?, url: String): List<BroadcastCandidate> {
        val playlist = cached ?: JSONObject(http.getString(url, userAgentHeaders()).also { report += "SpaceX CMS GET: ${url.substringAfter("spacex-website")}" })
        return playlist.optJSONArray("media")?.asObjects()?.mapNotNull { media ->
            val urls = media.playbackUrls(prefersMp4Playback) ?: return@mapNotNull null
            BroadcastCandidate(
                statusUrl = urls.first,
                dedupeKey = "spacex-starship-$kind:${media.cleanString("documentId") ?: media.cleanString("link") ?: urls.first}",
                streamUrl = null,
                fallbackStreamUrl = urls.second,
                title = media.cleanString("title") ?: "SpaceX ${kind.replaceFirstChar { it.uppercase() }}",
                subtitle = if (kind == "talk") media.bestStarshipTalkSubtitle() else media.bestSubtitle("Starship film"),
                tweetText = media.bestDescription(),
                publishedAt = media.cleanString("date").toInstantOrNull(),
                thumbnailUrl = media.optJSONObject("poster")?.bestImageUrl(),
                sourceKind = SourceKind.HLS,
                isAppendedSpaceXContent = true,
            )
        } ?: emptyList()
    }

    private suspend fun starshipFlightTestCandidates(prefersMp4Playback: Boolean, report: MutableList<String>, cache: JSONObject?): List<BroadcastCandidate> {
        val playlist = cache?.optJSONObject("starship_flight_tests_playlist")
            ?: JSONObject(http.getString(starshipFlightTestsPlaylistUrl, userAgentHeaders()).also { report += "SpaceX CMS GET: /media-playlist/starship-flight-tests" })
        val playlistCandidates = playlist.optJSONArray("media")?.asObjects()?.mapNotNull { media ->
            val urls = media.playbackUrls(prefersMp4Playback) ?: return@mapNotNull null
            BroadcastCandidate(
                statusUrl = urls.first,
                dedupeKey = "spacex-starship-flight-test:${media.mediaFlightTestKey() ?: media.cleanString("documentId") ?: urls.first}",
                streamUrl = null,
                fallbackStreamUrl = urls.second,
                title = media.cleanString("title") ?: "Starship Flight Test",
                subtitle = media.bestSubtitle("Starship flight test"),
                tweetText = media.bestDescription(),
                publishedAt = media.cleanString("date").toInstantOrNull(),
                thumbnailUrl = media.optJSONObject("poster")?.bestImageUrl(),
                sourceKind = SourceKind.HLS,
                isAppendedSpaceXContent = true,
            )
        } ?: emptyList()

        val tiles = cache?.optJSONArray("starship_launch_tiles") ?: JSONArray(http.getString(launchTilesUrl, userAgentHeaders()).also { report += "SpaceX CMS GET: /launches-page-tiles" })
        val missions = cache?.optJSONObject("starship_missions")
        val tileCandidates = tiles.asObjects()
            .filter { it.isStarshipFlightTestTile() }
            .map { tile ->
                val mission = missions?.optJSONObject(tile.optString("link"))
                val webcast = mission?.preferredWebcast()
                val sourceKind = webcast?.second ?: SourceKind.X_BROADCAST
                BroadcastCandidate(
                    statusUrl = webcast?.first ?: tile.sourceUrl(),
                    dedupeKey = "spacex-starship-flight-test:${tile.starshipFlightTestKey()}",
                    streamUrl = null,
                    title = tile.cleanString("shortTitle") ?: tile.cleanString("title") ?: "Starship Flight Test",
                    subtitle = "Starship flight test",
                    tweetText = mission?.missionSummary() ?: tile.cleanString("description"),
                    publishedAt = tile.cleanString("date").toInstantOrNull() ?: tile.cleanString("publishedAt").toInstantOrNull(),
                    thumbnailUrl = mission?.missionImageUrl() ?: tile.tileImageUrl(),
                    allowsDeferredStreamResolution = sourceKind == SourceKind.X_BROADCAST && webcast?.first != null,
                    sourceKind = sourceKind,
                    isAppendedSpaceXContent = true,
                    isUpcoming = webcast?.first == null,
                )
            }
        return (playlistCandidates + tileCandidates).deduplicated()
    }

    private fun parseTimeline(root: JSONObject): XApiTimeline {
        val includes = root.optJSONObject("includes")
        val media = includes?.optJSONArray("media")?.asObjects() ?: emptyList()
        val tweets = includes?.optJSONArray("tweets")?.asObjects() ?: emptyList()
        return XApiTimeline(
            posts = root.optJSONArray("data") ?: JSONArray(),
            mediaByKey = media.associateBy { it.getString("media_key") },
            includedPostsById = tweets.associateBy { it.getString("id") },
        )
    }

    private fun mediaObjects(post: JSONObject, mediaByKey: Map<String, JSONObject>): List<JSONObject> {
        val keys = post.optJSONObject("attachments")?.optJSONArray("media_keys") ?: return emptyList()
        return (0 until keys.length()).mapNotNull { mediaByKey[keys.getString(it)] }
    }

    private fun postMediaItems(media: List<JSONObject>): List<PostMediaItem> = media.mapNotNull { item ->
        val type = item.optString("type")
        if (type == "video" || type == "animated_gif") {
            val variant = bestVariant(item)
            PostMediaItem(
                id = item.optString("media_key"),
                kind = MediaKind.VIDEO,
                streamUrl = variant?.url,
                thumbnailUrl = item.optString("preview_image_url").ifBlank { null },
                width = item.optInt("width").takeIf { it > 0 },
                height = item.optInt("height").takeIf { it > 0 },
                altText = item.optString("alt_text").ifBlank { null },
            )
        } else if (type == "photo") {
            val url = item.optString("url").ifBlank { null } ?: return@mapNotNull null
            PostMediaItem(
                id = item.optString("media_key"),
                kind = MediaKind.PHOTO,
                thumbnailUrl = item.optString("preview_image_url").ifBlank { url },
                photoUrl = url,
                width = item.optInt("width").takeIf { it > 0 },
                height = item.optInt("height").takeIf { it > 0 },
                altText = item.optString("alt_text").ifBlank { null },
            )
        } else {
            null
        }
    }

    private fun bestVariant(media: JSONObject): MediaVariant? {
        val variants = media.optJSONArray("variants")?.asObjects()?.mapNotNull {
            val url = it.optString("url").takeIf { value -> value.startsWith("http") } ?: return@mapNotNull null
            MediaVariant(url, it.optString("content_type").ifBlank { null }, it.optInt("bit_rate", 0))
        } ?: emptyList()
        return variants.filter { it.contentType == "video/mp4" || it.url.substringBefore('?').endsWith(".mp4") }
            .maxByOrNull { it.bitRate }
            ?: variants.firstOrNull { it.contentType == "application/x-mpegURL" || it.url.substringBefore('?').endsWith(".m3u8") }
    }

    private suspend fun xApiGet(url: String, bearerToken: String, report: MutableList<String>): String {
        report += "X API GET: ${url.substringAfter("api.x.com")}"
        return http.getString(url, mapOf("Authorization" to "Bearer $bearerToken"))
    }

    private fun dedupeKey(
        post: JSONObject,
        statusUrl: String,
        linkedBroadcast: String?,
        mediaItems: List<PostMediaItem>,
        firstVideoStreamUrl: String?,
        galleryImages: List<GalleryImage>,
        referencedPostId: String?,
    ): String {
        linkedBroadcast?.xBroadcastId()?.let { return "broadcast:$it" }
        val videoCount = mediaItems.count { it.kind == MediaKind.VIDEO }
        val photoCount = mediaItems.count { it.kind == MediaKind.PHOTO }
        if (videoCount > 1 || (videoCount >= 1 && photoCount >= 1)) return "media-set:${referencedPostId ?: post.getString("id")}"
        if (firstVideoStreamUrl != null) return "stream:$firstVideoStreamUrl"
        if (galleryImages.isNotEmpty()) return "gallery:${referencedPostId ?: post.getString("id")}"
        post.optString("text").trim().lowercase().takeIf { it.isNotEmpty() }?.let { return "text:$it" }
        return "status:$statusUrl"
    }
}

private data class XApiTimeline(
    val posts: JSONArray,
    val mediaByKey: Map<String, JSONObject>,
    val includedPostsById: Map<String, JSONObject>,
) {
    fun copyPostsLimit(limit: Int): XApiTimeline {
        val limited = JSONArray()
        posts.asObjects().take(limit).forEach { limited.put(it) }
        return copy(posts = limited)
    }
}

private data class MediaVariant(val url: String, val contentType: String?, val bitRate: Int)

private data class BroadcastCandidate(
    val statusUrl: String,
    val dedupeKey: String,
    val streamUrl: String?,
    val fallbackStreamUrl: String? = null,
    val title: String,
    val subtitle: String,
    val tweetText: String? = null,
    val publishedAt: Instant? = null,
    val thumbnailUrl: String? = null,
    val galleryImages: List<GalleryImage> = emptyList(),
    val mediaItems: List<PostMediaItem> = emptyList(),
    val allowsDeferredStreamResolution: Boolean = false,
    val isPinned: Boolean = false,
    val sourceKind: SourceKind = SourceKind.X_BROADCAST,
    val isAppendedSpaceXContent: Boolean = false,
    val isUpcoming: Boolean = false,
    val originalPostId: String = statusUrl,
) {
    val requiresMediaCollection: Boolean
        get() = mediaItems.count { it.kind == MediaKind.VIDEO } > 1 ||
            (mediaItems.any { it.kind == MediaKind.VIDEO } && mediaItems.any { it.kind == MediaKind.PHOTO })

    val playableCollectionMediaItems: List<PostMediaItem>
        get() = mediaItems.filter { it.kind == MediaKind.PHOTO || (it.kind == MediaKind.VIDEO && it.streamUrl != null) }

    val xCardDuplicateKeys: List<String>
        get() = listOfNotNull(dedupeKey, "status:$originalPostId", title.normalizedTitle().takeIf { it.isNotBlank() }?.let { "title:$it" })

    fun toBroadcast(
        streamUrl: String? = this.streamUrl,
        thumbnailUrl: String? = this.thumbnailUrl,
        contentKind: ContentKind = ContentKind.VIDEO,
        mediaItems: List<PostMediaItem> = this.mediaItems,
        isLive: Boolean? = null,
    ): Broadcast = Broadcast(
        title = title,
        subtitle = subtitle,
        sourceUrl = statusUrl,
        sourceKind = sourceKind,
        contentKind = contentKind,
        streamUrl = streamUrl,
        fallbackStreamUrl = fallbackStreamUrl,
        tweetText = tweetText,
        publishedAt = publishedAt,
        thumbnailUrl = thumbnailUrl,
        galleryImages = galleryImages,
        mediaItems = mediaItems,
        isPinned = isPinned,
        isLive = isLive,
        isUpcoming = isUpcoming || (isLive != true && streamUrl == null && allowsDeferredStreamResolution),
    )
}

private fun tweetQuery(extra: List<Pair<String, String>>): String = (
    extra + listOf(
        "tweet.fields" to "created_at,entities,attachments,referenced_tweets",
        "expansions" to "attachments.media_keys,referenced_tweets.id,referenced_tweets.id.attachments.media_keys",
        "media.fields" to "type,variants,preview_image_url,url,width,height,media_key,alt_text",
    )
).joinToString("&") { (key, value) -> "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}" }

private fun JSONArray.asObjects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

private fun List<BroadcastCandidate>.deduplicated(): List<BroadcastCandidate> {
    val seen = mutableSetOf<String>()
    return filter { seen.add(it.dedupeKey) }
}

private fun List<BroadcastCandidate>.deduplicatedX(): List<BroadcastCandidate> {
    val result = mutableListOf<BroadcastCandidate>()
    val indexByKey = mutableMapOf<String, Int>()
    for (candidate in this) {
        val existingIndex = candidate.xCardDuplicateKeys.firstNotNullOfOrNull { indexByKey[it] }
        if (existingIndex == null) {
            val newIndex = result.size
            candidate.xCardDuplicateKeys.forEach { indexByKey[it] = newIndex }
            result += candidate
        } else {
            val existing = result[existingIndex]
            if (!existing.isPinned && (candidate.isPinned || ((candidate.publishedAt ?: Instant.MIN) > (existing.publishedAt ?: Instant.MIN)))) {
                result[existingIndex] = candidate
            }
            candidate.xCardDuplicateKeys.forEach { indexByKey[it] = existingIndex }
        }
    }
    return result
}

private fun JSONObject.broadcastTitle(): String =
    optString("text")
        .lines()
        .asSequence()
        .map { it.withoutLinks().trim() }
        .firstOrNull { it.isNotBlank() }
        ?.take(90)
        ?: "SpaceX Broadcast"

private fun JSONObject.broadcastUrlFromEntities(): String? {
    val urls = optJSONObject("entities")?.optJSONArray("urls")?.asObjects() ?: return null
    return urls.asSequence()
        .flatMap { sequenceOf(it.optString("expanded_url"), it.optString("unwound_url"), it.optString("url")) }
        .firstOrNull { it.contains("x.com/i/broadcasts/") || it.contains("twitter.com/i/broadcasts/") || it.contains("youtube.com/") || it.contains("youtu.be/") }
}

private fun JSONObject.referencedContentTweetId(): String? = optJSONArray("referenced_tweets")?.asObjects()
    ?.firstOrNull { it.optString("type") == "quoted" || it.optString("type") == "retweeted" }
    ?.optString("id")

private fun JSONObject.thumbnailUrlFromEntities(): String? =
    optJSONObject("entities")?.optJSONArray("urls")?.asObjects()
        ?.firstNotNullOfOrNull { it.optJSONArray("images")?.asObjects()?.maxByOrNull { image -> image.optInt("width") * image.optInt("height") }?.optString("url") }

private fun JSONObject.bestImageUrl(): String? {
    cleanString("url")?.let { return it }
    optJSONObject("formats")?.let { formats ->
        listOf("large", "medium", "small", "thumbnail").forEach { key ->
            formats.optJSONObject(key)?.cleanString("url")?.let { return it }
        }
    }
    return null
}

private fun JSONObject.playbackUrls(prefersMp4Playback: Boolean): Pair<String, String?>? {
    val mp4 = listOf("uhdLink", "hdLink", "link").firstNotNullOfOrNull { cleanString(it)?.takeIf { value -> value.startsWith("http") } }
    val hls = listOf("uhdStreamingLink", "hdStreamingLink", "streamingLink").firstNotNullOfOrNull { cleanString(it)?.takeIf { value -> value.startsWith("http") } }
    val primary = if (prefersMp4Playback) mp4 ?: hls else hls ?: mp4
    val fallback = if (primary == mp4) hls else mp4
    return primary?.let { it to fallback }
}

private fun JSONObject.bestDescription(): String? =
    cleanString("description") ?: cleanString("longDescription")

private fun JSONObject.bestSubtitle(prefix: String): String =
    cleanString("category") ?: prefix

private fun JSONObject.bestStarshipTalkSubtitle(): String = "Starship talk"

private fun JSONObject.mediaFlightTestKey(): String? =
    listOfNotNull(cleanString("title"), cleanString("link")).joinToString(" ").lowercase()
        .let { Regex("""flight[- ]?(\d+)""").find(it)?.groupValues?.getOrNull(1)?.let { number -> "flight-$number" } }

private fun JSONObject.isStarshipFlightTestTile(): Boolean =
    listOfNotNull(cleanString("title"), cleanString("shortTitle"), cleanString("link")).any { it.contains("starship", ignoreCase = true) }

private fun JSONObject.starshipFlightTestKey(): String =
    listOfNotNull(cleanString("shortTitle"), cleanString("title"), cleanString("link")).joinToString(" ").lowercase()
        .let { Regex("""flight[- ]?(\d+)""").find(it)?.groupValues?.getOrNull(1)?.let { number -> "flight-$number" } ?: cleanString("link").orEmpty() }

private fun JSONObject.sourceUrl(): String = "https://www.spacex.com/launches/${cleanString("link").orEmpty()}"

private fun JSONObject.tileImageUrl(): String? = optJSONObject("imageDesktop")?.bestImageUrl()

private fun JSONObject.missionImageUrl(): String? =
    optJSONObject("image")?.bestImageUrl()
        ?: optJSONObject("imageDesktop")?.bestImageUrl()
        ?: cleanString("imageUrl")

private fun JSONObject.preferredWebcast(): Pair<String, SourceKind>? {
    optJSONArray("webcasts")?.asObjects()?.let { webcasts ->
        val webcast = webcasts.firstOrNull { it.webcastSourceKind() == SourceKind.X_BROADCAST }
            ?: webcasts.firstOrNull { it.webcastSourceKind() == SourceKind.YOUTUBE }
        val videoId = webcast?.optString("videoId")?.ifBlank { null }
        val kind = webcast?.webcastSourceKind()
        if (videoId != null && kind != null) {
            return when (kind) {
                SourceKind.X_BROADCAST -> "https://x.com/i/broadcasts/$videoId" to kind
                SourceKind.YOUTUBE -> "https://www.youtube.com/watch?v=$videoId" to kind
                SourceKind.HLS -> null
            }
        }
    }
    val candidates = listOf("webcast", "youtube", "youtubeURL", "youtubeUrl", "xBroadcast", "broadcast", "broadcastURL", "broadcastUrl")
    val url = candidates.firstNotNullOfOrNull { key ->
        val value = opt(key)
        when (value) {
            is String -> value.takeIf { it.startsWith("http") }
            is JSONObject -> value.optString("url").takeIf { it.startsWith("http") }
            else -> null
        }
    } ?: return null
    val kind = if (url.contains("youtube.com") || url.contains("youtu.be")) SourceKind.YOUTUBE else SourceKind.X_BROADCAST
    return url to kind
}

private fun JSONObject.webcastSourceKind(): SourceKind? = when (optString("streamingVideoType").lowercase()) {
    "x.com", "x-live-studio" -> SourceKind.X_BROADCAST
    "youtube" -> SourceKind.YOUTUBE
    else -> null
}

private fun JSONObject.missionSummary(): String? =
    optJSONArray("paragraphs")?.asObjects()
        ?.firstNotNullOfOrNull { it.optString("content").stripHtml().trim().ifBlank { null } }

private fun String.stripHtml(): String = replace(Regex("<[^>]+>"), "")

private fun String.withoutLinks(): String =
    replace(Regex("""https?://\S+"""), "")
        .replace(Regex("""\s+"""), " ")

private fun JSONObject.cleanString(key: String): String? {
    val value = opt(key)
    if (value == null || value == JSONObject.NULL) return null
    return value.toString().trim().takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
}

private fun String.normalizedTitle(): String = lowercase().replace(Regex("""\s+"""), " ").trim()

private fun String.xBroadcastId(): String? {
    val marker = "/broadcasts/"
    val index = indexOf(marker)
    if (index < 0) return null
    return substring(index + marker.length).substringBefore('/').substringBefore('?').takeIf { it.isNotBlank() }
}

private fun String.isYouTubeUrl(): Boolean {
    val normalized = lowercase()
    return normalized.contains("youtube.com") || normalized.contains("youtu.be")
}

internal fun String?.toInstantOrNull(): Instant? = runCatching {
    if (isNullOrBlank()) null else Instant.parse(this)
}.getOrNull()
