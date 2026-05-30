package eu.kanade.tachiyomi.animeextension.ar.tuktukcinema

import aniyomi.lib.jsunpacker.JsUnpacker
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class VideoExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    fun videosFromUrl(url: String, host: String = "", resolution: String = ""): List<Video>{
        val prefix = host.ifBlank {
            url.toHttpUrl().host.substringBefore('.')
        }.replaceFirstChar(Char::titlecase)
        val quality = resolution.ifBlank { "Mirror" }
        val response = client.newCall(GET(url, headers)).execute()
        val document = response.asJsoup()

        val videoHeaders = headers.newBuilder().add("Referer", url).build()
        var playerData = response.body.string()
        
        // First, try to find all matches in the initial HTML
        var videoMatches = VIDEO_URL_REGEX.findAll(playerData).toList()
        
        // If no matches found, try unpacking script with eval
        if (videoMatches.isEmpty()) {
            playerData = document.selectFirst("script:containsData(eval)")?.data()
                ?.let(JsUnpacker::unpackAndCombine)
                ?: return emptyList()
            videoMatches = VIDEO_URL_REGEX.findAll(playerData).toList()
        }
        
        return videoMatches.flatMap { match ->
            val videoUrl = match.value.replace("\\/", "/")
            if("mp4" in videoUrl) {
                Video(videoUrl, "$prefix: $quality", videoUrl, headers = videoHeaders).let(::listOf)
            } else {
                playlistUtils.extractFromHls(videoUrl, url, videoNameGen = { quality -> "$prefix: $quality" })
            }
        }
    }

    companion object {
        private val PLAYER_SCRIPT_REGEX by lazy { Regex("""(?i)eval.*?(player|file|source)""") }
        private val VIDEO_URL_REGEX by lazy { Regex("""https?://[^\s\"'<>\\]+?\.(?:m3u8|mpd|mp4)(?:\?[^\"'<>\\]*)?""") }
    }
}