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
        val sourceElements = document.select("source[src]")
        if (sourceElements.isNotEmpty()) {
            val videoHeaders = headers.newBuilder().add("Referer", url).build()
            return sourceElements.map {
                val src = it.attr("src")
                Video(src, "$prefix: $quality", src, headers = videoHeaders)
            }
        }
        // return Video(url, "Other $prefix: $url", url).let(::listOf)
        // ==================== Script Search ===================
        val playerData = document.selectFirst("script:containsData(eval)")?.data()
            ?.let(JsUnpacker::unpackAndCombine)
            ?: response.body.string()
        
        return VIDEO_URL_REGEX.find(playerData)?.value?.replace("\\/", "/")?.let {
                if("mp4" in it) {
                    Video(it, "$prefix: $quality", it).let(::listOf)
                } else {
                    playlistUtils.extractFromHls(it, url, videoNameGen = { quality -> "$prefix: $quality" })
                }
            } ?: return emptyList()
    }

    companion object {
        private val PLAYER_SCRIPT_REGEX by lazy { Regex("""(?i)eval.*?(player|file|source)""") }
        private val VIDEO_URL_REGEX by lazy { Regex("""https?://[^\s\"'<>\\]+?\.(?:m3u8|mpd|mp4)(?:\?[^\"'<>\\]*)?""") }
    }
}