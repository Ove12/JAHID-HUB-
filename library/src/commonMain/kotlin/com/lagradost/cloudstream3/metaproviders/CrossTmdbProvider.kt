package com.lagradost.cloudstream3.metaproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class CrossTmdbProvider : TmdbProvider() {
    override var name = "JoyFlix Discover"
    override val apiName = "JoyFlix Discover"
    override var mainUrl = "https://www.themoviedb.org"
    override var lang = "en"
    override val useMetaLoadResponse = true
    override val usesWebView = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val includeAdult = true

    private val SearchResponse.yearOrNull: Int?
        get() = (this as? MovieSearchResponse)?.year ?: (this as? TvSeriesSearchResponse)?.year

    private fun filterName(name: String): String {
        return Regex("""[^a-zA-Z0-9]""").replace(name, "").lowercase()
    }

    private fun getTokens(name: String): Set<String> {
        val stopWords = setOf("the", "a", "an", "and", "of", "in", "to", "for", "with", "on", "at", "by", "from")
        return name.lowercase()
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() && it !in stopWords }
            .toSet()
    }

    private fun findMatchingItem(
        items: List<SearchResponse>,
        rawTitle: String,
        releaseYear: Int?,
        isTv: Boolean,
        season: Int? = null
    ): SearchResponse? {
        if (items.isEmpty() || rawTitle.isBlank()) return null

        val cleanTitle = filterName(rawTitle)
        val queryTokens = getTokens(rawTitle)
        if (queryTokens.isEmpty() && cleanTitle.isBlank()) return null

        val cleanNoYear = if (releaseYear != null) cleanTitle.removeSuffix("$releaseYear") else cleanTitle
        val seasonRegex = Regex("""(?:\bseason|\bs)\s*(\d+)""", RegexOption.IGNORE_CASE)

        var bestMatch: SearchResponse? = null
        var highestScore = -1000

        for (item in items) {
            val itemRawName = item.name
            val itemClean = filterName(itemRawName)
            if (itemClean.isBlank()) continue

            // 1. Media Type Hard Filter: TV must not match Movie, Movie must not match TV
            if (isTv) {
                if (item.type == TvType.Movie || item.type == TvType.AnimeMovie) {
                    continue
                }
            } else {
                if (item.type == TvType.TvSeries || item.type == TvType.AsianDrama) {
                    continue
                }
            }

            // 2. Season Specific Hard Filter for TV shows
            if (isTv && season != null) {
                val matchSeason = seasonRegex.find(itemRawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (matchSeason != null && matchSeason != season) {
                    // This item belongs to a completely different season (e.g. S1 or S4 when searching for S5)
                    continue
                }
            }

            val itemYear = item.yearOrNull
            val itemCleanNoYear = if (itemYear != null) itemClean.removeSuffix("$itemYear") else itemClean
            val itemTokens = getTokens(itemRawName)

            // 3. Token Coverage Check: Query tokens must be present in item
            val matchedTokensCount = if (queryTokens.isNotEmpty()) queryTokens.count { it in itemTokens } else 0
            val tokenRatio = if (queryTokens.isNotEmpty()) matchedTokensCount.toFloat() / queryTokens.size.toFloat() else 1.0f

            val isExact = itemClean == cleanTitle ||
                itemCleanNoYear == cleanNoYear ||
                itemClean == cleanNoYear ||
                itemCleanNoYear == cleanTitle

            if (!isExact) {
                if (queryTokens.size > 1 && tokenRatio < 0.75f) {
                    continue
                }
                if (queryTokens.size == 1 && matchedTokensCount == 0) {
                    continue
                }
            }

            var score = 0

            val prefDub = preferredDubProvider?.invoke(rawTitle, null)
            if (prefDub != null) {
                val itemHasDub = itemRawName.contains("dub", ignoreCase = true)
                if (prefDub && itemHasDub) {
                    score += 45
                } else if (prefDub && !itemHasDub) {
                    score -= 25
                } else if (!prefDub && itemHasDub) {
                    score -= 45
                } else if (!prefDub && !itemHasDub) {
                    score += 15
                }
            }

            // Title Similarity Scoring
            if (isExact) {
                score = 100
            } else {
                score = (tokenRatio * 70).toInt()

                // Extra tokens penalty (e.g. searching "Avatar", but item is "Avatar The Last Airbender")
                val ignoredExtras = setOf("season", "s", "complete", "series", "movie", "hd", "4k", "dual", "audio", "hindi", "dub", "sub")
                val extraTokens = (itemTokens - queryTokens).filter { token ->
                    token !in ignoredExtras && token.toIntOrNull() == null
                }
                if (extraTokens.isNotEmpty() && queryTokens.size <= 2) {
                    score -= (extraTokens.size * 15).coerceAtMost(45)
                }
            }

            // Length difference penalty
            val lenDiff = kotlin.math.abs(itemCleanNoYear.length - cleanNoYear.length)
            score -= (lenDiff * 2).coerceAtMost(25)

            // Year Matching
            if (releaseYear != null && itemYear != null) {
                val diff = itemYear - releaseYear
                if (diff == 0) {
                    score += 25
                } else if (kotlin.math.abs(diff) == 1) {
                    score += 10
                } else if (isTv && (season ?: 1) > 1 && diff > 1) {
                    // Later TV seasons can air years after premiere
                    score += 15
                } else {
                    // Different year for movies is almost always a different movie or reboot!
                    score -= if (!isTv) 80 else 40
                }
            }

            // Media Type Bonus
            if (isTv) {
                if (item.type == TvType.TvSeries || item.type == TvType.AsianDrama || item.type == TvType.Cartoon) {
                    score += 15
                }
            } else {
                if (item.type == TvType.Movie || item.type == TvType.AnimeMovie || item.type == TvType.Torrent) {
                    score += 15
                }
            }

            // Season Bonus
            if (isTv && season != null) {
                val matchSeason = seasonRegex.find(itemRawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (matchSeason == season) {
                    score += 35
                } else if (season == 1 && matchSeason == null) {
                    score += 10
                }
            }

            if (score > highestScore) {
                highestScore = score
                bestMatch = item
            }
        }

        // Must meet strict threshold of 60. NEVER fall back to items.firstOrNull()!
        return if (highestScore >= 60) {
            bestMatch
        } else {
            null
        }
    }

    interface ScraperListener {
        fun onScrapersDiscovered(scrapers: List<String>)
        fun onScraperStarted(scraper: String)
        fun onScraperFinished(scraper: String, linksCount: Int)
    }

    companion object {
        var scraperListener: ScraperListener? = null
        var forceSearchAll: Boolean = false
        var preferredScraperProvider: ((mediaTitle: String?, tmdbId: Int?) -> String?)? = null
        var preferredDubProvider: ((mediaTitle: String?, tmdbId: Int?) -> Boolean?)? = null

        fun isAnimeApiStatic(api: com.lagradost.cloudstream3.MainAPI): Boolean {
            val types = api.supportedTypes
            val isPureAnime = types.isNotEmpty() && types.all {
                it == TvType.Anime || it == TvType.AnimeMovie || it == TvType.OVA
            }
            val hasAnimeNoGeneral = types.contains(TvType.Anime) &&
                !types.contains(TvType.Movie) &&
                !types.contains(TvType.TvSeries)
            val nameLower = api.name.lowercase()
            val isAnimeName = nameLower.contains("anime") ||
                nameLower.contains("aniwave") ||
                nameLower.contains("gogo") ||
                nameLower.contains("zoro") ||
                nameLower.contains("marin") ||
                nameLower.contains("yomiroll") ||
                nameLower.contains("aniwatch") ||
                nameLower.contains("allanime") ||
                nameLower.contains("nineanime") ||
                nameLower.contains("kickass")

            return isPureAnime || hasAnimeNoGeneral || isAnimeName
        }

        fun getValidApisStatic(): List<com.lagradost.cloudstream3.MainAPI> {
            return (apis + com.lagradost.cloudstream3.APIHolder.allProviders).distinctBy { it.name }.filter {
                it.name != "JoyFlix Discover" &&
                it !is TmdbProvider &&
                it.name != "All" &&
                it.name != "None" &&
                it.name != "Random" &&
                !it.name.contains("IPTV", ignoreCase = true) &&
                !it.supportedTypes.all { type -> type == TvType.Live } &&
                !com.lagradost.cloudstream3.NsfwHelper.isBlocked(it.name, it.mainUrl, it.supportedTypes)
            }
        }

        fun getAvailableScrapers(isAnime: Boolean = false, isTv: Boolean = false): List<String> {
            val valid = getValidApisStatic()
            return valid.filter { api ->
                if (!isAnime && isAnimeApiStatic(api)) {
                    return@filter false
                }
                true
            }.map { it.name }
        }

        fun isValidLink(link: ExtractorLink): Boolean {
            val urlLower = link.url.lowercase()
            val nameLower = link.name.lowercase()
            val sourceLower = link.source.lowercase()

            if (link.url.isBlank()) return false
            if (urlLower.contains("youtube.com") || urlLower.contains("youtu.be")) return false
            if (nameLower.contains("trailer") || nameLower.contains("teaser") ||
                nameLower.contains("sample") || nameLower.contains("promo") ||
                nameLower.contains("preview")
            ) return false
            if (nameLower.contains("netflix") || sourceLower.contains("netflix") ||
                nameLower.contains("netmirror") || sourceLower.contains("netmirror")
            ) return false

            return true
        }
    }

    private val validApis: List<com.lagradost.cloudstream3.MainAPI>
        get() = getValidApisStatic()

    @Serializable
    data class CrossMetaData(
        @JsonProperty("isSuccess") @SerialName("isSuccess") val isSuccess: Boolean,
        @JsonProperty("movies") @SerialName("movies") val movies: List<Pair<String, String>>? = null,
    )

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        return super.search(query, page)
    }

    override suspend fun load(url: String): LoadResponse? {
        return super.load(url)
    }

    private suspend fun loadUniversalEmbeds(
        tmdbId: Int?,
        imdbId: String?,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null && imdbId.isNullOrBlank()) return

        val embedUrls = mutableListOf<String>()

        if (tmdbId != null) {
            if (isTv && season != null && episode != null) {
                embedUrls.add("https://vidsrc.me/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode")
                embedUrls.add("https://vidsrc.net/embed/tv/$tmdbId/$season/$episode")
                embedUrls.add("https://vidsrc.pm/embed/tv/$tmdbId/$season/$episode")
                embedUrls.add("https://vidsrc.io/embed/tv/$tmdbId/$season/$episode")
                embedUrls.add("https://vidsrc.to/embed/tv/$tmdbId/$season/$episode")
                embedUrls.add("https://2embed.cc/embedtv/$tmdbId&s=$season&e=$episode")
                embedUrls.add("https://multiembed.mov/?video_id=$tmdbId&tmdb=1&s=$season&e=$episode")
                embedUrls.add("https://autoembed.co/tv/tmdb/$tmdbId-$season-$episode")
            } else {
                embedUrls.add("https://vidsrc.me/embed/movie?tmdb=$tmdbId")
                embedUrls.add("https://vidsrc.net/embed/movie/$tmdbId")
                embedUrls.add("https://vidsrc.pm/embed/movie/$tmdbId")
                embedUrls.add("https://vidsrc.io/embed/movie/$tmdbId")
                embedUrls.add("https://vidsrc.to/embed/movie/$tmdbId")
                embedUrls.add("https://2embed.cc/embed/$tmdbId")
                embedUrls.add("https://multiembed.mov/?video_id=$tmdbId&tmdb=1")
                embedUrls.add("https://autoembed.co/movie/tmdb/$tmdbId")
            }
        }

        if (!imdbId.isNullOrBlank()) {
            if (isTv && season != null && episode != null) {
                embedUrls.add("https://vidsrc.me/embed/tv?imdb=$imdbId&season=$season&episode=$episode")
                embedUrls.add("https://vidsrc.net/embed/tv?imdb=$imdbId&season=$season&episode=$episode")
                embedUrls.add("https://vidsrc.pm/embed/tv?imdb=$imdbId&season=$season&episode=$episode")
                embedUrls.add("https://vidsrc.io/embed/tv?imdb=$imdbId&season=$season&episode=$episode")
                embedUrls.add("https://multiembed.mov/?video_id=$imdbId&s=$season&e=$episode")
                embedUrls.add("https://autoembed.co/tv/imdb/$imdbId-$season-$episode")
            } else {
                embedUrls.add("https://vidsrc.me/embed/movie?imdb=$imdbId")
                embedUrls.add("https://vidsrc.net/embed/movie?imdb=$imdbId")
                embedUrls.add("https://vidsrc.pm/embed/movie?imdb=$imdbId")
                embedUrls.add("https://vidsrc.io/embed/movie?imdb=$imdbId")
                embedUrls.add("https://multiembed.mov/?video_id=$imdbId")
                embedUrls.add("https://autoembed.co/movie/imdb/$imdbId")
            }
        }

        embedUrls.amap { embedUrl ->
            try {
                if (loadExtractor(embedUrl, subtitleCallback) { link ->
                    if (isValidLink(link)) callback(link)
                }) {
                    return@amap
                }
                val response = app.get(
                    embedUrl,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                        "Referer" to "https://www.google.com/"
                    ),
                    timeout = 15_000L
                )
                val html = response.text
                if (html.isNotBlank()) {
                    val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    val iframeUrls = iframeRegex.findAll(html).map { it.groupValues[1] }.take(3).toList()

                    for (iframeUrl in iframeUrls) {
                        try {
                            val resolvedUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
                            val resolvedLower = resolvedUrl.lowercase()
                            if (resolvedLower.contains("youtube.com") ||
                                resolvedLower.contains("youtu.be") ||
                                resolvedLower.contains("trailer") ||
                                resolvedLower.contains("promo") ||
                                resolvedLower.contains("preview") ||
                                resolvedLower.contains("adserver") ||
                                resolvedLower.contains("popunder")
                            ) {
                                continue
                            }
                            loadExtractor(resolvedUrl, embedUrl, subtitleCallback) { link ->
                                if (isValidLink(link)) {
                                    callback(link)
                                }
                            }
                        } catch (e: Exception) {
                            logError(e)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun isAnimeApi(api: com.lagradost.cloudstream3.MainAPI): Boolean = isAnimeApiStatic(api)


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAnyLink = false
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            foundAnyLink = true
            callback(link)
        }

        // 1. Backwards compatibility with cached CrossMetaData
        tryParseJson<CrossMetaData>(data)?.let { metaData ->
            if (metaData.isSuccess) {
                metaData.movies?.amap { (apiName, movieData) ->
                    getApiFromNameNull(apiName)?.let { api ->
                        try {
                            api.loadLinks(movieData, isCasting, subtitleCallback, wrappedCallback)
                        } catch (e: Exception) {
                            logError(e)
                        }
                    }
                }
                return foundAnyLink
            }
        }

        // 2. Resolve TmdbLink across all active scraping provider extensions
        var resolvedLink = tryParseJson<TmdbLink>(data)
        if (resolvedLink == null) {
            val tmdbUrlRegex = Regex("""themoviedb\.org/(movie|tv)/(\d+)""")
            val match = tmdbUrlRegex.find(data)
            if (match != null) {
                val isTvType = match.groupValues[1] == "tv"
                val id = match.groupValues[2].toIntOrNull()
                if (id != null) {
                    try {
                        val path = if (isTvType) "/tv/$id" else "/movie/$id"
                        val json = getApi(path, mapOf("language" to "en-US", "append_to_response" to "external_ids"))
                        if (isTvType) {
                            val detail = parseJson<TmdbTvDetail>(json)
                            resolvedLink = TmdbLink(
                                imdbID = detail.externalIds?.imdbId,
                                tmdbID = id,
                                episode = 1,
                                season = 1,
                                movieName = detail.displayTitle,
                                year = detail.year,
                                isAnime = detail.isAnime
                            )
                        } else {
                            val detail = parseJson<TmdbMovieDetail>(json)
                            resolvedLink = TmdbLink(
                                imdbID = detail.imdbId ?: detail.externalIds?.imdbId,
                                tmdbID = id,
                                episode = null,
                                season = null,
                                movieName = detail.displayTitle,
                                year = detail.year,
                                isAnime = detail.isAnime
                            )
                        }
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }
        }

        val tmdbLink = resolvedLink ?: return false
        var title = tmdbLink.movieName
        if (title.isNullOrBlank() && tmdbLink.tmdbID != null) {
            try {
                val isTvType = tmdbLink.season != null && tmdbLink.episode != null
                val path = if (isTvType) "/tv/${tmdbLink.tmdbID}" else "/movie/${tmdbLink.tmdbID}"
                val json = getApi(path, mapOf("language" to "en-US"))
                val nameRegex = Regex(""""(?:title|name)"\s*:\s*"([^"]+)"""")
                title = nameRegex.find(json)?.groupValues?.getOrNull(1)
            } catch (e: Exception) {
                logError(e)
            }
        }
        if (title.isNullOrBlank()) return false

        val cleanTitle = filterName(title)
        val season = tmdbLink.season
        val episode = tmdbLink.episode
        val isTv = season != null && episode != null
        val imdbId = tmdbLink.imdbID
        val tmdbId = tmdbLink.tmdbID
        val releaseYear = tmdbLink.year
        val isAnime = tmdbLink.isAnime

        val available = validApis.filter { api ->
            // ANIME NA HOLE ANIME EXTENSION / ANIME SITE-A KONO REQUEST JABE NA
            if (!isAnime && isAnimeApi(api)) {
                return@filter false
            }
            true
        }

        val shouldSearchAll = forceSearchAll
        forceSearchAll = false

        val preferredScraper = if (!shouldSearchAll) preferredScraperProvider?.invoke(title, tmdbId) else null
        val preferredApi = if (!preferredScraper.isNullOrBlank()) {
            available.firstOrNull { it.name.equals(preferredScraper, ignoreCase = true) }
        } else null

        if (preferredApi != null) {
            scraperListener?.onScrapersDiscovered(listOf(preferredApi.name))
            val linksCount = scrapeApi(
                api = preferredApi,
                title = title,
                season = season,
                episode = episode,
                isTv = isTv,
                imdbId = imdbId,
                releaseYear = releaseYear,
                isCasting = isCasting,
                subtitleCallback = subtitleCallback,
                wrappedCallback = wrappedCallback
            )
            // If preferred scraper returned links, PLAY IMMEDIATELY and DO NOT query any other scraper!
            if (linksCount > 0) {
                return true
            }
        }

        // If preferredApi was null, or returned 0 links (e.g. missing episode), query remaining scrapers
        val remainingApis = if (preferredApi != null) available.filter { it != preferredApi } else available
        if (remainingApis.isNotEmpty()) {
            val scraperNames = remainingApis.map { it.name }
            scraperListener?.onScrapersDiscovered(scraperNames)
            remainingApis.amap { api ->
                scrapeApi(
                    api = api,
                    title = title,
                    season = season,
                    episode = episode,
                    isTv = isTv,
                    imdbId = imdbId,
                    releaseYear = releaseYear,
                    isCasting = isCasting,
                    subtitleCallback = subtitleCallback,
                    wrappedCallback = wrappedCallback
                )
            }
        }

        // 3. Fallback to universal embeds if extensions returned no playable stream links
        if (!foundAnyLink) {
            try {
                scraperListener?.onScraperStarted("Universal Embeds")
                var embedCount = 0
                loadUniversalEmbeds(
                    tmdbId = tmdbId,
                    imdbId = imdbId,
                    isTv = isTv,
                    season = season,
                    episode = episode,
                    subtitleCallback = subtitleCallback,
                    callback = { extLink ->
                        if (!isValidLink(extLink)) return@loadUniversalEmbeds
                        embedCount++
                        wrappedCallback(
                            ExtractorLink(
                                source = "Universal Embeds",
                                name = extLink.name,
                                url = extLink.url,
                                referer = extLink.referer,
                                quality = extLink.quality,
                                headers = extLink.headers,
                                extractorData = extLink.extractorData,
                                type = extLink.type,
                                audioTracks = extLink.audioTracks,
                            )
                        )
                    }
                )
                scraperListener?.onScraperFinished("Universal Embeds", embedCount)
            } catch (e: Exception) {
                logError(e)
            }
        }

        return foundAnyLink || available.isNotEmpty()
    }

    private suspend fun scrapeApi(
        api: com.lagradost.cloudstream3.MainAPI,
        title: String,
        season: Int?,
        episode: Int?,
        isTv: Boolean,
        imdbId: String?,
        releaseYear: Int?,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        wrappedCallback: (ExtractorLink) -> Unit
    ): Int {
        scraperListener?.onScraperStarted(api.name)
        var linksFromThisScraper = 0
        try {
            var loaded: LoadResponse? = null

            // Try IMDb direct sync URL if supported
            if (!imdbId.isNullOrBlank() && api.supportedSyncNames.contains(SyncIdName.Imdb)) {
                try {
                    val syncUrl = api.getLoadUrl(SyncIdName.Imdb, imdbId)
                    if (!syncUrl.isNullOrBlank()) {
                        val syncLoaded = api.load(syncUrl)
                        if (syncLoaded != null) {
                            loaded = syncLoaded
                        }
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }

            // If direct sync not available, search with title variations
            if (loaded == null) {
                val searchQueries = linkedSetOf<String>()
                if (isTv && season != null && season > 1) {
                    searchQueries.add("$title Season $season")
                    searchQueries.add("$title S$season")
                    searchQueries.add("$title $season")
                }
                val prefDub = preferredDubProvider?.invoke(title, null)
                if (prefDub == true) {
                    searchQueries.add("$title (Dub)")
                    searchQueries.add("$title Dub")
                }
                searchQueries.add(title)
                if (title.contains("&")) {
                    searchQueries.add(title.replace("&", "and"))
                    searchQueries.add(title.replace("&", " "))
                }
                if (title.contains(":")) {
                    searchQueries.add(title.substringBefore(":").trim())
                    searchQueries.add(title.replace(":", " "))
                }
                val cleanQuery = title.replace(Regex("""[^a-zA-Z0-9\s]"""), " ")
                    .replace(Regex("""\s+"""), " ").trim()
                if (cleanQuery.isNotBlank() && !cleanQuery.equals(title, ignoreCase = true)) {
                    if (isTv && season != null && season > 1) {
                        searchQueries.add("$cleanQuery Season $season")
                    }
                    searchQueries.add(cleanQuery)
                }

                for (q in searchQueries) {
                    try {
                        val res = api.search(q)
                        if (!res.isNullOrEmpty()) {
                            val match = findMatchingItem(res, title, releaseYear, isTv, season)
                            if (match != null) {
                                val matchLoaded = api.load(match.url)
                                if (matchLoaded != null) {
                                    if (isTv && season != null && season > 1 && matchLoaded is TvSeriesLoadResponse) {
                                        val hasSeason = matchLoaded.episodes.any { it.season == season } ||
                                            matchLoaded.name.contains("season $season", ignoreCase = true) ||
                                            matchLoaded.name.contains("season$season", ignoreCase = true) ||
                                            matchLoaded.name.contains("s$season", ignoreCase = true) ||
                                            matchLoaded.url.contains("season-$season", ignoreCase = true) ||
                                            matchLoaded.url.contains("season$season", ignoreCase = true) ||
                                            matchLoaded.url.contains("s$season", ignoreCase = true)
                                        if (hasSeason) {
                                            loaded = matchLoaded
                                            break
                                        }
                                    } else if (!isTv && matchLoaded is MovieLoadResponse) {
                                        loaded = matchLoaded
                                        break
                                    } else if (isTv && (season == null || season <= 1) && matchLoaded is TvSeriesLoadResponse) {
                                        loaded = matchLoaded
                                        break
                                    }
                                }
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
            }

            if (loaded != null) {
                val targetData: String? = if (isTv) {
                    when (loaded) {
                        is TvSeriesLoadResponse -> {
                            val pageName = loaded.name.lowercase()
                            val pageUrl = loaded.url.lowercase()
                            val isDedicatedSeasonPage = if (season != null) {
                                pageName.contains("season $season") ||
                                    pageName.contains("season$season") ||
                                    pageName.contains("s$season") ||
                                    pageUrl.contains("season-$season") ||
                                    pageUrl.contains("season$season") ||
                                    pageUrl.contains("s$season")
                            } else false

                            val targetEp = loaded.episodes.firstOrNull { ep ->
                                ep.season == season && ep.episode == episode
                            } ?: if (isDedicatedSeasonPage) {
                                loaded.episodes.firstOrNull { ep ->
                                    ep.episode == episode || (ep.season == season && ep.episode == episode)
                                } ?: (if (episode != null && episode > 0) loaded.episodes.getOrNull(episode - 1) else null)
                            } else if (season == 1 || season == null) {
                                loaded.episodes.firstOrNull { ep ->
                                    (ep.season == 1 || ep.season == null || ep.season == 0) && ep.episode == episode
                                } ?: (if (episode != null && episode > 0) loaded.episodes.getOrNull(episode - 1) else null)
                            } else {
                                val seasonEps = loaded.episodes.filter { it.season == season }
                                if (seasonEps.isNotEmpty()) {
                                    seasonEps.firstOrNull { it.episode == episode }
                                } else {
                                    null
                                }
                            }

                            targetEp?.data
                        }
                        else -> null
                    }
                } else {
                    when (loaded) {
                        is MovieLoadResponse -> {
                            loaded.dataUrl.ifBlank { null } ?: loaded.url
                        }
                        else -> null
                    }
                }

                if (!targetData.isNullOrBlank()) {
                    api.loadLinks(targetData, isCasting, subtitleCallback) { extLink ->
                        if (!isValidLink(extLink)) return@loadLinks
                        linksFromThisScraper++
                        val streamName = if (extLink.name.contains(api.name, ignoreCase = true)) {
                            extLink.name
                        } else {
                            "[${api.name}] ${extLink.name}"
                        }
                        @Suppress("DEPRECATION")
                        wrappedCallback(
                            ExtractorLink(
                                source = api.name,
                                name = streamName,
                                url = extLink.url,
                                referer = extLink.referer,
                                quality = extLink.quality,
                                headers = extLink.headers,
                                extractorData = extLink.extractorData,
                                type = extLink.type,
                                audioTracks = extLink.audioTracks,
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logError(e)
        } finally {
            scraperListener?.onScraperFinished(api.name, linksFromThisScraper)
        }
        return linksFromThisScraper
    }
}

