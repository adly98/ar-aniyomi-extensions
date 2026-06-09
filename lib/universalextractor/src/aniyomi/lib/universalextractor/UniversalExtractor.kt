package aniyomi.lib.universalextractor

import aniyomi.lib.jsunpacker.JsUnpacker
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.synchrony.Deobfuscator
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class UniversalExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils = PlaylistUtils(client)

    fun videosFromUrl(url: String, host: String = "", resolution: String? = ""): List<Video> {
        val prefix = host.ifBlank {
            url.toHttpUrl().host.substringBefore('.')
        }
        val quality = resolution?.ifBlank { "Mirror" }
        val document = client.newCall(GET(url, headers)).execute().asJsoup()
        return Video(url, url, url).let(::listOf)
        val videoHeaders = headers.newBuilder().apply {
            set("Referer", url)
            if ("uqload.is" in url) {
                set("Upgrade-Insecure-Requests", "1")
            }
        }.build()

        val videoSources = document.select("source")
        if (videoSources.isNotEmpty()) {
            return videoSources.map {
                val src = it.attr("src")
                Video(src, "$prefix: $quality", src)
            }
        }

        val videoMatches = mutableListOf<MatchResult>()
        document.select("script").forEach { scriptElement ->
            val script = scriptElement.data()
            if (script.isNotEmpty()) {
                videoMatches.addAll(VIDEO_URL_REGEX.findAll(resolveScript(script)))
            }
        }

        if (videoMatches.isNotEmpty()) {
            val videos = mutableListOf<Video>()
            for (match in videoMatches) {
                val videoUrl = match.value
                if (videoUrl.contains("mp4")) {
                    videos.add(Video(videoUrl, "$prefix: $quality", videoUrl, headers = videoHeaders))
                } else {
                    videos.addAll(playlistUtils.extractFromHls(videoUrl, url, videoNameGen = { streamQuality -> "$prefix: $streamQuality" }))
                }
            }
            return videos
        }
        return listOf(Video(url, url, url))
    }

    /**
     * Resolves and deobfuscates a potentially obfuscated or packed JavaScript string.
     *
     * @param script The JavaScript code as a string to be resolved.
     * @return The deobfuscated and unpacked JavaScript code as a string.
     */
    private fun resolveScript(script: String): String {
        val deobfuscatedScript = if (OBFUSCATED_SCRIPT_REGEX.containsMatchIn(script)) {
            Deobfuscator.deobfuscateScript(script) ?: script
        } else {
            script
        }
        val unpackedScript = if (PLAYER_SCRIPT_REGEX.containsMatchIn(deobfuscatedScript)) {
            JsUnpacker.unpackAndCombine(deobfuscatedScript) ?: deobfuscatedScript
        } else {
            deobfuscatedScript
        }
        return unpackedScript
    }

    fun isSupported(url: String): Boolean {
        val host = url.toHttpUrl().host
        return host in SUPPORTED_HOSTS
    }

    companion object {
        private val SUPPORTED_HOSTS = arrayOf("krakenfiles.com", "uqload.is", "stmruby.com", "minochinos.com", "smoothpre.com", "vidtube.one", "earnvids.xyz", "luluvdo.com")
        private val PLAYER_SCRIPT_REGEX by lazy { Regex("""(?i)eval\s*\(\s*.*?(player|file|source|mp4|m3u8).*?\)""") }
        private val VIDEO_URL_REGEX by lazy { Regex("""https?://[^\s"'<>\\]{7,}\.(?:m3u8|mpd|mp4)(?:\?[^"'<>\\]*)?""") }
        private val OBFUSCATED_SCRIPT_REGEX by lazy { Regex("""(\\\\/|\\x[0-9a-f]{2}|\\u[0-9a-f]{4}|\\[0-7]{1,3}|\\b[a-zA-Z0-9+/]{20,}={0,2}\\b|\[[^]]*]\[[^]]*]|${'$'}_[A-Za-z0-9]+|atob|String\.fromCharCode|decodeURIComponent)""", RegexOption.IGNORE_CASE) }
    }
}
