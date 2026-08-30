package com.sighmon.spacextv.data

import java.time.Instant
import java.util.UUID

enum class SourceKind { X_BROADCAST, HLS, YOUTUBE }
enum class ContentKind { VIDEO, GALLERY, COLLECTION }
enum class MediaKind { VIDEO, PHOTO }

data class GalleryImage(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    val altText: String? = null,
)

data class PostMediaItem(
    val id: String,
    val kind: MediaKind,
    val streamUrl: String? = null,
    val thumbnailUrl: String? = null,
    val photoUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val altText: String? = null,
)

data class Broadcast(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val subtitle: String,
    val sourceUrl: String,
    val sourceKind: SourceKind,
    val contentKind: ContentKind = ContentKind.VIDEO,
    val streamUrl: String? = null,
    val fallbackStreamUrl: String? = null,
    val tweetText: String? = null,
    val publishedAt: Instant? = null,
    val thumbnailUrl: String? = null,
    val galleryImages: List<GalleryImage> = emptyList(),
    val mediaItems: List<PostMediaItem> = emptyList(),
    val isPinned: Boolean = false,
    val isLive: Boolean? = null,
    val isUpcoming: Boolean = false,
) {
    val videoMediaItems: List<PostMediaItem> get() = mediaItems.filter { it.kind == MediaKind.VIDEO }
    val photoMediaItems: List<PostMediaItem> get() = mediaItems.filter { it.kind == MediaKind.PHOTO }
    val isStarshipFlightTest: Boolean get() = subtitle.startsWith("Starship flight test", ignoreCase = true)

    val mediaSummaryLabel: String?
        get() = when (contentKind) {
            ContentKind.GALLERY -> plural(galleryImages.size, "photo")
            ContentKind.COLLECTION -> mediaSummary(mediaItems)
            ContentKind.VIDEO -> null
        }

    fun videoBroadcast(item: PostMediaItem, index: Int, totalVideos: Int): Broadcast = copy(
        id = UUID.randomUUID().toString(),
        subtitle = if (totalVideos > 1) "Video $index of $totalVideos" else subtitle,
        contentKind = ContentKind.VIDEO,
        streamUrl = item.streamUrl,
        thumbnailUrl = item.thumbnailUrl ?: thumbnailUrl,
        galleryImages = emptyList(),
        mediaItems = emptyList(),
    )

    fun photoGalleryBroadcast(images: List<GalleryImage> = galleryImagesFromMediaItems()): Broadcast = copy(
        id = UUID.randomUUID().toString(),
        contentKind = ContentKind.GALLERY,
        streamUrl = null,
        thumbnailUrl = images.firstOrNull()?.url ?: thumbnailUrl,
        galleryImages = images,
        mediaItems = emptyList(),
    )

    private fun galleryImagesFromMediaItems(): List<GalleryImage> {
        if (galleryImages.isNotEmpty()) return galleryImages
        return photoMediaItems.mapNotNull { item ->
            val url = item.photoUrl ?: item.thumbnailUrl ?: return@mapNotNull null
            GalleryImage(url, item.width, item.height, item.altText)
        }
    }
}

data class ResolvedBroadcast(
    val title: String?,
    val streamUrl: String,
    val thumbnailUrl: String? = null,
    val isLive: Boolean? = null,
)

data class NextLaunch(
    val title: String,
    val vehicle: String?,
    val launchSite: String?,
    val launchDate: Instant,
    val windowCloseDate: Instant?,
    val isLaunchTimePrecise: Boolean,
    val sourceUrl: String,
    val imageUrl: String?,
)

data class DiscoveryResult(
    val broadcasts: List<Broadcast>,
    val debugLines: List<String>,
    val xApiCacheGeneratedAt: Instant? = null,
    val cardCheckHits: Int = 0,
    val cardCheckMisses: Int = 0,
)

fun mediaSummary(items: List<PostMediaItem>): String? {
    val videos = items.count { it.kind == MediaKind.VIDEO }
    val photos = items.count { it.kind == MediaKind.PHOTO }
    return listOfNotNull(plural(videos, "video"), plural(photos, "photo"))
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" - ")
}

private fun plural(count: Int, singular: String): String? = when (count) {
    0 -> null
    1 -> "1 $singular"
    else -> "$count ${singular}s"
}
