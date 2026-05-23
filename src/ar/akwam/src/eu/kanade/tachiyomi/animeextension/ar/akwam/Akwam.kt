package eu.kanade.tachiyomi.animeextension.ar.akwam

import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Akwam : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "أكوام"

    private val preferences by getPreferencesLazy()

    override val baseUrl
        get() = preferences.customDomain.ifBlank { "https://ak.sv" }

    override val lang = "ar"

    override val supportsLatest = false

    // ============================== Popular ===============================

    override fun popularAnimeSelector(): String = "div.entry-box-1 div.entry-image a.box"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/movies?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        title = element.select("picture img").attr("alt")
        thumbnail_url = element.select("picture img").attr("data-src").replace("178x260", "360x480")
        setUrlWithoutDomain(element.attr("href"))
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li.page-item a[rel=next]"

    // ============================== Episodes ==============================

    override fun episodeListSelector() = "div.bg-primary2 h2 a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        fun addEpisodes(document: Document) {
            if (document.select(episodeListSelector()).isNullOrEmpty()) {
                // add movie
                document.select("input#reportInputUrl").map { episodes.add(episodeFromElement(it)) }
            } else {
                document.select(episodeListSelector()).map { episodes.add(episodesFromElement(it)) }
            }
        }
        addEpisodes(response.asJsoup())
        return episodes
    }

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("value"))
        name = "مشاهدة"
    }

    private fun episodesFromElement(element: Element): SEpisode = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.text()
        episode_number = element.text().getEpisodeNumber()
    }

    private fun String.getEpisodeNumber(): Float {
        return this.substringBefore(":").filter { it.isDigit() }.toFloatOrNull() ?: 1F
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val showId = document.select("a.link-show").attr("href").substringAfter("watch/")
        val watchId = document.select("input#page_id").attr("value")
        val iframe = "$baseUrl/watch/$showId/$watchId"
        val referer = response.request.url.toString()
        val refererHeaders = Headers.headersOf("referer", referer)
        val iframeResponse = client.newCall(GET(iframe, refererHeaders)).execute().asJsoup()
        return iframeResponse.select(videoListSelector()).map { videoFromElement(it) }
    }

    override fun videoListSelector() = "source"

    override fun videoFromElement(element: Element): Video = Video(
            element.attr("src").replace("https", "http"),
            element.attr("size") + "p",
            element.attr("src").replace("https", "http"),
        )

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.quality
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val searchSection = filterList.filterIsInstance<SearchSection>().first()
        val searchRating = filterList.filterIsInstance<SearchRating>().first()
        val searchFormat = filterList.filterIsInstance<SearchFormat>().first()
        val searchQuality = filterList.filterIsInstance<SearchQuality>().first()
        val typeFilter = filterList.filterIsInstance<TypeFilter>().first()
        val sectionFilter = filterList.filterIsInstance<SectionFilter>().first()
        val categoryFilter = filterList.filterIsInstance<CategoryFilter>().first()
        val ratingFilter = filterList.filterIsInstance<RatingFilter>().first()
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) {
                addPathSegment("search")
                addQueryParameter("q", query)
                if (searchSection.state != 0) {
                    addQueryParameter("section", searchSection.toUriPart())
                }
                if (searchRating.state != 0) {
                    addQueryParameter("rating", searchRating.toUriPart())
                }
                if (searchFormat.state != 0) {
                    addQueryParameter("formats", searchFormat.toUriPart())
                }
                if (searchQuality.state != 0) {
                    addQueryParameter("quality", searchQuality.toUriPart())
                }
            } else {
                addPathSegment(typeFilter.toUriPart())
                if (sectionFilter.state != 0) {
                    url.addQueryParameter("section", sectionFilter.toUriPart())
                }
                if (categoryFilter.state != 0) {
                    addQueryParameter("category", categoryFilter.toUriPart())
                }
                if (ratingFilter.state != 0) {
                    addQueryParameter("rating", ratingFilter.toUriPart())
                }
            }
            addQueryParameter("page", page.toString())
        }
        return GET(url, headers)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.select("picture > img.img-fluid").attr("alt")
        genre =
            document.select("div.font-size-16.d-flex.align-items-center.mt-3 a.badge, span.badge-info, span:contains(جودة الفيلم), span:contains(انتاج)")
                .joinToString(", ") { it.text().replace("جودة الفيلم : ", "") }
        author = document.select("span:contains(انتاج)").text().replace("انتاج : ", "")
        description = document.select("div.widget:contains(قصة )").text()
        status = SAnime.COMPLETED
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime =
        throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    // ============================ Filters =============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("فلترات البحث"),
        AnimeFilter.Separator(),
        SearchSection(),
        SearchRating(),
        SearchFormat(),
        SearchQuality(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("تصفح الموقع (تعمل فقط لو كان البحث فارغ)"),
        TypeFilter(),
        SectionFilter(),
        CategoryFilter(),
        RatingFilter(),
    )

    private class SearchSection(vals: Array<Pair<String?, String>>) :
        PairFilter(
            "بحث عن",
            arrayOf(
                Pair("0", "الكل"),
                Pair("movie", "افلام"),
                Pair("series", "مسلسلات"),
                Pair("show", "تلفزيون"),
            )
        )

    private class SearchRating(vals: Array<Pair<String?, String>>) :
        PairFilter(
            "التقيم",
            arrayOf(
                Pair("0", "الكل"),
                Pair("1", "1+"),
                Pair("2", "2+"),
                Pair("3", "3+"),
                Pair("4", "4+"),
                Pair("5", "5+"),
                Pair("6", "6+"),
                Pair("7", "7+"),
                Pair("8", "8+"),
                Pair("9", "9+"),
            )
        )

    private class SearchFormat(vals: Array<Pair<String?, String>>) :
        PairFilter(
            "الجودة",
            arrayOf(
                Pair("0", "الكل"),
                Pair("BluRay", "BluRay"),
                Pair("WebRip", "WebRip"),
                Pair("BRRIP", "BRRIP"),
                Pair("DVDrip", "DVDrip"),
                Pair("DVDSCR", "DVDSCR"),
                Pair("HD", "HD"),
                Pair("HDTS", "HDTS"),
                Pair("HDTV", "HDTV"),
                Pair("CAM", "CAM"),
                Pair("WEB-DL", "WEB-DL"),
                Pair("HDTC", "HDTC"),
                Pair("BDRIP", "BDRIP"),
                Pair("HDRIP", "HDRIP"),
                Pair("HC+HDRIP", "HC HDRIP"),
            )
        )

    private class SearchQuality(vals: Array<Pair<String?, String>>) :
        PairFilter(
            "الدقة",
            arrayOf(
                Pair("0", "الكل"),
                Pair("240p", "240p"),
                Pair("360p", "360p"),
                Pair("480p", "480p"),
                Pair("720p", "720p"),
                Pair("1080p", "1080p"),
                Pair("3D", "3D"),
                Pair("4K", "4K"),
            )
        )

    private class TypeFilter(vals: Array<Pair<String?, String>>) :
        PairFilter(
            "النوع",
            arrayOf(
                Pair("0", "الكل"),
                Pair("movies", "افلام"),
                Pair("series", "مسلسلات"),
            )
        )

    private class SectionFilter(vals: Array<Pair<String?, String>>) :
        PairFilter(
            "القسم",
            arrayOf(
                Pair("0", "القسم"),
                Pair("29", "عربي"),
                Pair("30", "اجنبي"),
                Pair("31", "هندي"),
                Pair("32", "تركي"),
                Pair("33", "اسيوي"),
            )
        )

    private class CategoryFilter(vals: Array<Pair<String?, String>>) :
        PairFilter(
            "التصنيف",
            arrayOf(
                Pair("0", "التصنيف"),
                Pair("87", "رمضان"),
                Pair("30", "انمي"),
                Pair("18", "اكشن"),
                Pair("71", "مدبلج"),
                Pair("72", "NETFLIX"),
                Pair("20", "كوميدي"),
                Pair("35", "اثارة"),
                Pair("34", "غموض"),
                Pair("33", "عائلي"),
                Pair("88", "اطفال"),
                Pair("25", "حربي"),
                Pair("32", "رياضي"),
                Pair("89", "قصير"),
                Pair("43", "فانتازيا"),
                Pair("24", "خيال علمي"),
                Pair("31", "موسيقى"),
                Pair("29", "سيرة ذاتية"),
                Pair("28", "وثائقي"),
                Pair("27", "رومانسي"),
                Pair("26", "تاريخي"),
                Pair("23", "دراما"),
                Pair("22", "رعب"),
                Pair("21", "جريمة"),
                Pair("19", "مغامرة"),
                Pair("91", "غربي"),
            )
        )

    private class RatingFilter(vals: Array<Pair<String?, String>>) :
        PairFilter(
            "التقييم",
            arrayOf(
                Pair("0", "التقييم"),
                Pair("1", "1+"),
                Pair("2", "2+"),
                Pair("3", "3+"),
                Pair("4", "4+"),
                Pair("5", "5+"),
                Pair("6", "6+"),
                Pair("7", "7+"),
                Pair("8", "8+"),
                Pair("9", "9+"),
            )
        )

    open class PairFilter(displayName: String, private val vals: Array<Pair<String?, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.second }.toTypedArray()) {
        fun toUriPart() = vals[state].first
    }

    // =============================== Search ===============================
    private var SharedPreferences.customDomain by preferences.delegate(PREF_DOMAIN_CUSTOM_KEY, "")
    private var SharedPreferences.quality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = intl["preferred_quality"],
            entries = listOf("1080p", "720p", "480p", "360p", "240p"),
            entryValues = listOf("1080", "720", "480", "360", "240"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addEditTextPreference(
            key = PREF_DOMAIN_CUSTOM_KEY,
            default = "",
            title = "رابط الموقع",
            dialogMessage = "أدخل رابط الموقع (على سبيل المثال، https://example.com)",
            summary = preferences.customDomain,
            getSummary = { it },
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            validate = { it.isBlank() || (it.toHttpUrlOrNull() != null && !it.endsWith("/")) },
            validationMessage = { "عنوان URL غير صالح أو مشوه أو ينتهي بشرطة مائلة" },
        )
    }

    companion object {
        private const val PREF_DOMAIN_CUSTOM_KEY = "custom_domain"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}
