package com.sighmon.spacextv.data

import org.json.JSONObject
import java.time.Instant

class LaunchScheduleService(private val http: HttpClient = HttpClient()) {
    private val tilesUrl = "https://content.spacex.com/api/spacex-website/launches-page-tiles/upcoming"
    private val timingsUrl = "https://sxcontent9668.azureedge.us/cms-assets/future_missions.json"

    suspend fun nextLaunch(now: Instant = Instant.now()): NextLaunch {
        val tiles = org.json.JSONArray(http.getString(tilesUrl, userAgentHeaders())).asObjectsPublic()
        val timings = JSONObject(http.getString(timingsUrl, userAgentHeaders()))
        val launches = tiles.mapNotNull { tile ->
            val timing = timings.optJSONObject(tile.optString("correlationId")) ?: return@mapNotNull null
            val launchDate = timing.launchDate() ?: return@mapNotNull null
            NextLaunch(
                title = tile.optString("shortTitle").ifBlank { tile.optString("title").ifBlank { "SpaceX Launch" } },
                vehicle = tile.optString("vehicle").ifBlank { null },
                launchSite = tile.optString("launchSite").ifBlank { null },
                launchDate = launchDate,
                windowCloseDate = timing.optJSONObject("PrimaryLaunchWindow")?.optJSONObject("Close")?.timestamp(),
                isLaunchTimePrecise = timing.optBoolean("IsPrimaryLaunchTimeGiven"),
                sourceUrl = "https://www.spacex.com/launches/${tile.optString("link")}",
                imageUrl = tile.optJSONObject("imageDesktop")?.bestLaunchImageUrl(),
            )
        }
        return launches.filter { !it.launchDate.isBefore(now) }.minByOrNull { it.launchDate }
            ?: launches.minByOrNull { it.launchDate }
            ?: throw IllegalStateException("No upcoming SpaceX launch was returned.")
    }
}

private fun org.json.JSONArray.asObjectsPublic(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject.launchDate(): Instant? {
    if (!optBoolean("TZeroPaused")) optJSONObject("TZeroLaunchDate")?.timestamp()?.let { return it }
    return optJSONObject("PrimaryLaunchWindow")?.optJSONObject("Open")?.timestamp()
        ?: optJSONObject("PrimaryLaunchDate")?.timestamp()
}

private fun JSONObject.timestamp(): Instant? {
    val seconds = optDouble("Seconds", Double.NaN)
    return if (seconds.isNaN()) null else Instant.ofEpochSecond(seconds.toLong())
}

private fun JSONObject.bestLaunchImageUrl(): String? {
    optJSONObject("formats")?.optJSONObject("large")?.optString("url")?.ifBlank { null }?.let { return it }
    return optString("url").ifBlank { null }
}
