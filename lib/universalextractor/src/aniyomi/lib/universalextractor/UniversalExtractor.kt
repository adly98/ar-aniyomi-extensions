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
        }.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val quality = resolution?.ifBlank { "Mirror" }
        val document = client.newCall(GET(url, headers)).execute().asJsoup()
        val videoHeaders = headers.newBuilder().set("Referer", url).build()

        val videoSources = document.select("source")
        if (videoSources.isNotEmpty()) {
            return videoSources.map {
                val src = it.attr("src")
                Video(src, "$prefix: $quality", src)
            }
        }

        val videoMatches = document.select("script").flatMap { scriptElement ->
            scriptElement.data().takeIf { it.isNotEmpty() }?.let {
                VIDEO_URL_REGEX.findAll(resolveScript(it)).toList()
            } ?: emptyList()
        }

        if (videoMatches.isNotEmpty()) {
            return videoMatches.flatMap { match ->
                val videoUrl = match.value
                if (videoUrl.contains("mp4")) {
                    Video(videoUrl, "$prefix: $quality", videoUrl, headers = videoHeaders).let(::listOf)
                } else {
                    playlistUtils.extractFromHls(videoUrl, url, videoNameGen = { streamQuality -> "$prefix: $streamQuality" })
                }
            }
        }
        return emptyList()
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

    companion object {
        private val PLAYER_SCRIPT_REGEX by lazy { Regex("""(?i)eval\s*\(\s*.*?(player|file|source|mp4|m3u8).*?\)""") }
        private val VIDEO_URL_REGEX by lazy { Regex("""https?://[^\s"'<>\\]{7,}\.(?:m3u8|mpd|mp4)(?:\?[^"'<>\\]*)?""") }
        private val OBFUSCATED_SCRIPT_REGEX by lazy { Regex("""(\\\\/|\\x[0-9a-f]{2}|\\u[0-9a-f]{4}|\\[0-7]{1,3}|\\b[a-zA-Z0-9+/]{20,}={0,2}\\b|\[[^]]*]\[[^]]*]|${'$'}_[A-Za-z0-9]+|atob|String\.fromCharCode|decodeURIComponent)""", RegexOption.IGNORE_CASE) }
    }
}
