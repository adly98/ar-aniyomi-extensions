package eu.kanade.tachiyomi.animeextension.ar.tuktukcinema

import aniyomi.lib.jsunpacker.JsUnpacker
import aniyomi.lib.synchrony.Deobfuscator
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class VideoExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    fun videosFromUrl(url: String, host: String = "", resolution: String = ""): List<Video> {
        val prefix = host.ifBlank {
            url.toHttpUrl().host.substringBefore('.')
        }.replaceFirstChar { it.uppercaseChar() }
        val quality = resolution.ifBlank { "Mirror" }
        val response = client.newCall(GET(url, headers)).execute()
        val playerData = response.body.string()
        val document = playerData.asJsoup()

        val videoHeaders = headers.newBuilder().add("Referer", url).build()
        
        // First, try to find all matches in the initial HTML
        var videoMatches = VIDEO_URL_REGEX.findAll(playerData).toList()
        
        // If still no matches found, search for obfuscated scripts using regex
        if (videoMatches.isEmpty()) {
            videoMatches = document.select("script").flatMap { scriptElement ->
                val scriptData = scriptElement.data()
                scriptData.takeIf { it.isNotEmpty() }?.let {
                    val resolvedData = resolveScript(it)
                    VIDEO_URL_REGEX.findAll(resolvedData).toList()
                } ?: emptyList()
            }
        }
        
        if (videoMatches.isEmpty()) {
            return emptyList()
        }
        
        return videoMatches.flatMap { match ->
            if (videoUrl.contains("mp4")) {
                listOf(Video(videoUrl, "$prefix: $quality", videoUrl, headers = videoHeaders))
            } else {
                playlistUtils.extractFromHls(videoUrl, url, videoNameGen = { streamQuality -> "$prefix: $streamQuality" })
            }
        }
    }

    /**
     * Resolves and deobfuscates a potentially obfuscated or packed JavaScript string.
     *
     * @param script The JavaScript code as a string to be resolved.
     * @return The deobfuscated and unpacked JavaScript code as a string.
     */
    private fun resolveScript(script: String): String {
        var scriptData = script
        if (OBFUSCATED_SCRIPT_REGEX.containsMatchIn(scriptData)) {
            Deobfuscator.deobfuscateScript(scriptData)?.let { scriptData = it }
        }
        if (PLAYER_SCRIPT_REGEX.containsMatchIn(scriptData)) {
            scriptData = JsUnpacker.unpackAndCombine(scriptData)
        }
        return scriptData
    }

    companion object {
        private val PLAYER_SCRIPT_REGEX by lazy { Regex("""(?i)eval\s*\(\s*.*?(player|file|source|mp4|m3u8).*?\)""") }
        private val VIDEO_URL_REGEX by lazy { Regex("""https?://[^\s\"'<>\\]+?\.(?:m3u8|mpd|mp4)(?:\?[^\"'<>\\]*)?""") }
        private val OBFUSCATED_SCRIPT_REGEX by lazy { Regex("""(\\\\/|\\x[0-9a-f]{2}|\\u[0-9a-f]{4}|\\[0-7]{1,3}|\\b[a-zA-Z0-9+/]{20,}={0,2}\\b|\[[^\]]*\]\[[^\]]*\]|\$_[A-Za-z0-9]+|atob|String\.fromCharCode|decodeURIComponent)""", RegexOption.IGNORE_CASE) }
    }
}
