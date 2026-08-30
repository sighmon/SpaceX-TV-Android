package com.sighmon.spacextv.data

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

class BroadcastResolver(private val http: HttpClient = HttpClient()) {
    private data class XWebConfiguration(
        val bearerToken: String,
        val tweetResultByRestIdQueryId: String?,
    )

    suspend fun resolve(broadcast: Broadcast): ResolvedBroadcast {
        return when (broadcast.sourceKind) {
            SourceKind.HLS -> ResolvedBroadcast(broadcast.title, broadcast.sourceUrl, broadcast.thumbnailUrl)
            SourceKind.YOUTUBE -> throw IllegalStateException("YouTube launch pages open outside the native player.")
            SourceKind.X_BROADCAST -> {
                val stream = broadcast.streamUrl
                if (stream != null) {
                    ResolvedBroadcast(broadcast.title, highestQualityStreamUrl(stream), broadcast.thumbnailUrl)
                } else {
                    val resolved = resolveStatusUrl(broadcast.sourceUrl)
                    resolved.copy(title = broadcast.title, thumbnailUrl = broadcast.thumbnailUrl ?: resolved.thumbnailUrl)
                }
            }
        }
    }

    suspend fun resolveStatusUrl(statusUrl: String): ResolvedBroadcast {
        val broadcastId = xBroadcastId(statusUrl)
        if (broadcastId != null) return xBroadcastStream(broadcastId)

        val body = normalizePageBody(http.getString(statusUrl, userAgentHeaders()))
        val stream = Regex("""https://[^"'<>\s\\]+\.(?:m3u8|mp4)(?:\?[^"'<>\s\\]+)?""", RegexOption.IGNORE_CASE)
            .findAll(body)
            .map { it.value }
            .firstOrNull { it.contains("pscp.tv") || it.substringBefore('?').endsWith(".m3u8") }
            ?: throw IllegalStateException("No playable HLS or MP4 stream was found for this status.")

        return ResolvedBroadcast(null, highestQualityStreamUrl(stream), pageThumbnailUrl(body))
    }

    private suspend fun xBroadcastStream(broadcastId: String): ResolvedBroadcast {
        val webConfiguration = xWebConfiguration()
        val bearer = webConfiguration.bearerToken
        val guest = xGuestToken(bearer)
        try {
            val broadcast = xBroadcast(broadcastId, bearer, guest)
            if (broadcast.hasNotStarted()) {
                throw NotStartedException(notStartedMessage(broadcast.scheduledStart()))
            }
            val mediaKey = broadcast.optString("media_key")
            if (mediaKey.isBlank()) throw IllegalStateException("No playable HLS or MP4 stream was found for this broadcast.")
            val source = xLiveVideoSource(mediaKey, bearer, guest)
            val stream = source.optString("noRedirectPlaybackUrl").ifBlank { source.optString("location") }
            if (stream.isBlank()) throw IllegalStateException("No playable HLS or MP4 stream was found for this broadcast.")
            val thumbnail = bestThumbnailUrl(broadcast)
                ?: source.optString("thumbnail_url").ifBlank { null }
                ?: runCatching { broadcastPageThumbnailUrl(broadcastId) }.getOrNull()
            return ResolvedBroadcast(
                title = broadcast.optString("status").ifBlank { null },
                streamUrl = highestQualityStreamUrl(stream),
                thumbnailUrl = thumbnail,
                isLive = broadcast.optString("state").equals("running", ignoreCase = true),
            )
        } catch (error: NotStartedException) {
            throw IllegalStateException(error.message)
        } catch (error: Exception) {
            if (!broadcastId.all { it.isDigit() }) throw error
            return xTweetBroadcastStream(broadcastId, bearer, guest, webConfiguration.tweetResultByRestIdQueryId)
        }
    }

    private suspend fun xTweetBroadcastStream(tweetId: String, bearer: String, guest: String, queryId: String?): ResolvedBroadcast {
        val broadcast = xTweetBroadcast(tweetId, bearer, guest, queryId)
        if (broadcast.hasNotStarted()) {
            throw IllegalStateException(notStartedMessage(broadcast.tweetScheduledStart()))
        }
        val mediaKey = broadcast.optString("media_key")
        if (mediaKey.isBlank()) throw IllegalStateException("No playable HLS or MP4 stream was found for this broadcast.")
        val source = xLiveVideoSource(mediaKey, bearer, guest)
        val stream = source.optString("noRedirectPlaybackUrl").ifBlank { source.optString("location") }
        if (stream.isBlank()) throw IllegalStateException("No playable HLS or MP4 stream was found for this broadcast.")
        val thumbnail = broadcast.optString("thumbnail_url").ifBlank { null } ?: source.optString("thumbnail_url").ifBlank { null }
        return ResolvedBroadcast(
            title = broadcast.optString("status").ifBlank { null },
            streamUrl = highestQualityStreamUrl(stream),
            thumbnailUrl = thumbnail,
            isLive = broadcast.optString("state").equals("running", ignoreCase = true),
        )
    }

    private suspend fun xGuestToken(bearer: String): String {
        val data = http.postBytes(
            "https://api.x.com/1.1/guest/activate.json",
            xWebHeaders(bearer),
        )
        return JSONObject(data.decodeToString()).getString("guest_token")
    }

    private suspend fun xBroadcast(id: String, bearer: String, guest: String): JSONObject {
        val body = http.getString(
            "https://api.x.com/1.1/broadcasts/show.json?ids=$id",
            xWebHeaders(bearer, guest),
        )
        return JSONObject(body).getJSONObject("broadcasts").getJSONObject(id)
    }

    private suspend fun xTweetBroadcast(tweetId: String, bearer: String, guest: String, queryId: String?): JSONObject {
        if (queryId.isNullOrBlank()) throw IllegalStateException("The app could not resolve X web playback credentials.")
        val variables = jsonQueryValue(
            mapOf(
                "tweetId" to tweetId,
                "withCommunity" to false,
                "includePromotedContent" to false,
                "withVoice" to false,
            )
        )
        val url = "https://x.com/i/api/graphql/$queryId/TweetResultByRestId" +
            "?variables=${urlEncode(variables)}" +
            "&features=${urlEncode(jsonQueryValue(tweetResultFeatures))}" +
            "&fieldToggles=${urlEncode(jsonQueryValue(tweetResultFieldToggles))}"
        val body = http.getString(url, xWebHeaders(bearer, guest))
        val card = JSONObject(body)
            .getJSONObject("data")
            .getJSONObject("tweetResult")
            .getJSONObject("result")
            .optJSONObject("card")
            ?: throw IllegalStateException("No playable HLS or MP4 stream was found for this broadcast.")
        val bindingValues = card.getJSONObject("legacy").getJSONArray("binding_values")
        val values = (0 until bindingValues.length()).associate {
            val item = bindingValues.getJSONObject(it)
            item.getString("key") to item.getJSONObject("value")
        }
        return JSONObject().apply {
            values["broadcast_media_key"]?.optString("string_value")?.ifBlank { null }?.let { put("media_key", it) }
            values["broadcast_title"]?.optString("string_value")?.ifBlank { null }?.let { put("status", it) }
            values["broadcast_state"]?.optString("string_value")?.ifBlank { null }?.let { put("state", it) }
            values["broadcast_scheduled_start_time"]?.optString("string_value")?.ifBlank { null }?.let { put("scheduled_start_ms", it) }
            listOf(
                values["broadcast_thumbnail_original"]?.optJSONObject("image_value")?.optString("url"),
                values["broadcast_thumbnail_x_large"]?.optJSONObject("image_value")?.optString("url"),
                values["broadcast_thumbnail"]?.optJSONObject("image_value")?.optString("url"),
                values["broadcast_pre_live_slate_x_large"]?.optJSONObject("image_value")?.optString("url"),
            ).firstOrNull { !it.isNullOrBlank() }?.let { put("thumbnail_url", it) }
        }
    }

    private suspend fun xLiveVideoSource(mediaKey: String, bearer: String, guest: String): JSONObject {
        val body = http.getString(
            "https://api.x.com/1.1/live_video_stream/status/$mediaKey",
            xWebHeaders(bearer, guest),
        )
        return JSONObject(body).getJSONObject("source")
    }

    private suspend fun xWebBearerToken(): String {
        return xWebConfiguration().bearerToken
    }

    private suspend fun xWebConfiguration(): XWebConfiguration {
        val home = http.getString("https://x.com/", userAgentHeaders())
        var bearerToken = webBearerToken(home)
        var tweetResultQueryId = tweetResultByRestIdQueryId(home)
        var candidates = webScriptUrls(home)
        val visited = linkedSetOf<String>()

        while (candidates.isNotEmpty() && visited.size < 15 && (bearerToken == null || tweetResultQueryId == null)) {
            val scriptUrl = candidates.first()
            candidates = candidates.drop(1)
            if (!visited.add(scriptUrl)) continue
            val script = runCatching { http.getString(scriptUrl, userAgentHeaders()) }.getOrNull() ?: continue
            if (bearerToken == null) bearerToken = webBearerToken(script)
            if (tweetResultQueryId == null) tweetResultQueryId = tweetResultByRestIdQueryId(script)
            val seen = visited.toMutableSet()
            candidates = prioritizeWebScripts(webScriptUrls(script, scriptUrl) + candidates).filter { seen.add(it) }
        }

        if (bearerToken == null) throw IllegalStateException("The app could not resolve X web playback credentials.")
        return XWebConfiguration(bearerToken, tweetResultQueryId)
    }

    private fun xWebHeaders(bearer: String, guest: String? = null): Map<String, String> = buildMap {
        put("Authorization", "Bearer $bearer")
        put("x-twitter-client-language", "en")
        put("x-twitter-active-user", "yes")
        guest?.let { put("x-guest-token", it) }
    }

    private fun xBroadcastId(url: String): String? {
        val marker = "/broadcasts/"
        val index = url.indexOf(marker)
        if (index < 0) return null
        return url.substring(index + marker.length).substringBefore('/').substringBefore('?').takeIf { it.isNotBlank() }
    }

    private fun normalizePageBody(body: String): String = body
        .replace("\\/", "/")
        .replace("\\\\u002F", "/")
        .replace("\\u002F", "/")
        .replace("%2F", "/")
        .replace("%3A", ":")
        .replace("%3F", "?")
        .replace("%3D", "=")
        .replace("%26", "&")
        .replace("&amp;", "&")

    private fun pageThumbnailUrl(body: String): String? {
        val patterns = listOf(
            """"og:image"\s+content="([^"]+)"""",
            """"thumbnailUrl"\s*:\s*"([^"]+)"""",
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            Regex(pattern).find(body)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
        }
    }

    private fun webScriptUrls(body: String, baseUrl: String? = null): List<String> {
        val seen = linkedSetOf<String>()
        Regex("""https://abs\.twimg\.com/(?:responsive-web/client-web|x-web/x-web)/[^"'<>\s`]+\.js\b""")
            .findAll(body)
            .forEach { seen += it.value }
        if (baseUrl != null) {
            Regex("""["']((?:\./|\.\./)?(?:assets/)?[^"'<>\s`]+\.js)["']""")
                .findAll(body)
                .map { it.groupValues[1] }
                .filter { it.startsWith("./") || it.startsWith("../") || it.startsWith("assets/") }
                .forEach { relative -> resolveWebScriptUrl(relative, baseUrl)?.let { seen += it } }
        }
        return prioritizeWebScripts(seen.toList())
    }

    private fun webBearerToken(body: String): String? {
        val patterns = listOf("""Bearer ([A-Za-z0-9%._-]+)""", """["'`](AAAAAAAA[A-Za-z0-9%._-]+)["'`]""")
        return patterns.firstNotNullOfOrNull { Regex(it).find(body)?.groupValues?.getOrNull(1) }
    }

    private fun tweetResultByRestIdQueryId(body: String): String? =
        Regex("""queryId:"([^"]+)",operationName:"TweetResultByRestId"""").find(body)?.groupValues?.getOrNull(1)
            ?: Regex("""operationName:"TweetResultByRestId",queryId:"([^"]+)"""").find(body)?.groupValues?.getOrNull(1)

    private fun resolveWebScriptUrl(relative: String, baseUrl: String): String? {
        return runCatching {
            if (relative.startsWith("assets/")) {
                URI(xWebPackageRoot(baseUrl)).resolve(relative).toString()
            } else {
                URI(baseUrl).resolve(relative).toString()
            }
        }.getOrNull()
    }

    private fun xWebPackageRoot(url: String): String {
        val uri = URI(url)
        val path = uri.path
        val rootPath = if (path.contains("/assets/")) {
            path.substringBefore("/assets/") + "/"
        } else {
            path.substringBeforeLast('/', "") + "/"
        }
        return URI(uri.scheme, uri.authority, rootPath, null, null).toString()
    }

    private fun prioritizeWebScripts(urls: List<String>): List<String> =
        urls.distinct().sortedWith(compareBy<String> { webScriptPriority(it) }.thenBy { it })

    private fun webScriptPriority(url: String): Int {
        val name = url.substringAfterLast('/')
        return when {
            name.startsWith("guest-token-") -> 0
            name.startsWith("main.") -> 1
            name.startsWith("entry-") || name.contains("entry-client") -> 2
            else -> 3
        }
    }

    private fun jsonQueryValue(value: Map<String, Any>): String = JSONObject(value).toString()

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private suspend fun broadcastPageThumbnailUrl(broadcastId: String): String? {
        val body = normalizePageBody(http.getString("https://x.com/i/broadcasts/$broadcastId", userAgentHeaders()))
        return pageThumbnailUrl(body)
    }

    private fun bestThumbnailUrl(broadcast: JSONObject): String? {
        val direct = listOf(
            "image_url_original",
            "image_url_large",
            "image_url_medium",
            "image_url",
            "image_url_small",
            "thumbnail_url_large",
            "thumbnail_url_medium",
            "thumbnail_url",
            "thumbnail_url_small",
            "pre_live_slate_url",
        ).firstNotNullOfOrNull { key -> broadcast.optString(key).ifBlank { null } }
        if (direct != null) return direct
        val widths = broadcast.optJSONObject("image_x_sizes") ?: return null
        return widths.keys().asSequence()
            .mapNotNull { key -> key.toIntOrNull()?.let { it to widths.optString(key) } }
            .filter { it.second.isNotBlank() }
            .maxByOrNull { it.first }
            ?.second
    }

    private suspend fun highestQualityStreamUrl(url: String): String {
        // Keep HLS playback on the master playlist. Signed variant URLs can expire during long sessions.
        return url
    }
}

private class NotStartedException(message: String) : Exception(message)

private val tweetResultFeatures: Map<String, Any> = mapOf(
    "creator_subscriptions_tweet_preview_api_enabled" to true,
    "premium_content_api_read_enabled" to true,
    "communities_web_enable_tweet_community_results_fetch" to true,
    "c9s_tweet_anatomy_moderator_badge_enabled" to true,
    "responsive_web_grok_analyze_button_fetch_trends_enabled" to true,
    "responsive_web_grok_analyze_post_followups_enabled" to true,
    "rweb_cashtags_composer_attachment_enabled" to true,
    "responsive_web_jetfuel_frame" to true,
    "responsive_web_grok_share_attachment_enabled" to true,
    "responsive_web_grok_annotations_enabled" to true,
    "articles_preview_enabled" to true,
    "responsive_web_edit_tweet_api_enabled" to true,
    "graphql_is_translatable_rweb_tweet_is_translatable_enabled" to true,
    "view_counts_everywhere_api_enabled" to true,
    "longform_notetweets_consumption_enabled" to true,
    "responsive_web_twitter_article_tweet_consumption_enabled" to true,
    "tweet_awards_web_tipping_enabled" to false,
    "responsive_web_grok_show_grok_translated_post" to true,
    "responsive_web_grok_analysis_button_from_backend" to true,
    "standardized_nudges_misinfo" to true,
    "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled" to true,
    "longform_notetweets_rich_text_read_enabled" to true,
    "longform_notetweets_inline_media_enabled" to true,
    "responsive_web_grok_image_annotation_enabled" to true,
    "responsive_web_grok_imagine_annotation_enabled" to true,
    "responsive_web_grok_community_note_auto_translation_is_enabled" to true,
    "responsive_web_enhance_cards_enabled" to true,
)

private val tweetResultFieldToggles: Map<String, Any> = mapOf(
    "withArticleRichContentState" to true,
    "withArticlePlainText" to true,
    "withGrokAnalyze" to true,
    "withDisallowedReplyControls" to true,
)

private fun JSONObject.hasNotStarted(): Boolean = when (optString("state").lowercase()) {
    "not_started", "pre_published" -> true
    else -> false
}

private fun JSONObject.scheduledStart(): java.time.Instant? {
    val raw = opt("scheduled_start_ms") ?: return null
    val millis = when (raw) {
        is Number -> raw.toLong()
        is String -> raw.toLongOrNull()
        else -> null
    } ?: return null
    return java.time.Instant.ofEpochMilli(millis)
}

private fun JSONObject.tweetScheduledStart(): java.time.Instant? = scheduledStart()

private fun notStartedMessage(scheduledStart: java.time.Instant?): String {
    if (scheduledStart == null) return "This livestream has not started yet. Check back closer to the scheduled start time."
    return if (scheduledStart.isAfter(java.time.Instant.now())) {
        "Live coverage is scheduled to start $scheduledStart."
    } else {
        "Live coverage was scheduled for $scheduledStart and should begin soon."
    }
}
