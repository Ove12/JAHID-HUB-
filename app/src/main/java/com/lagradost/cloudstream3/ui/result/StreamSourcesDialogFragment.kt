package com.lagradost.cloudstream3.ui.result

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.isMovie
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.metaproviders.CrossTmdbProvider
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.player.Cache
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.LOADTYPE_ALL
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.VideoGenerator
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object StreamSourcesCache {
    data class CacheEntry(
        val activeScrapers: List<String>,
        val providerLinks: Map<String, List<ExtractorLink>>,
        val allLoadedLinks: List<ExtractorLink>,
        val allLoadedSubs: List<SubtitleData>,
        val timestamp: Long = System.currentTimeMillis()
    )

    val cache = ConcurrentHashMap<String, CacheEntry>()

    fun getCacheKey(episode: ResultEpisode?, response: LoadResponse?): String {
        val parsedLink = tryParseJson<TmdbLink>(episode?.data ?: "")
            ?: tryParseJson<TmdbLink>((response as? MovieLoadResponse)?.dataUrl ?: "")
        val tmdbId = parsedLink?.tmdbID?.toString()
            ?: Regex("""themoviedb\.org/(?:movie|tv)/(\d+)""").find(response?.url ?: episode?.data ?: "")
                ?.groupValues?.getOrNull(1)
            ?: response?.syncData?.get("tmdb")
            ?: (response?.url?.hashCode()?.toString())
            ?: (episode?.id?.toString())
            ?: "default"
        val season = parsedLink?.season ?: episode?.season ?: 0
        val ep = parsedLink?.episode ?: episode?.episode ?: 0
        return "${tmdbId}_s${season}_e${ep}"
    }

    fun get(key: String): CacheEntry? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > 30 * 60 * 1000) {
            cache.remove(key)
            return null
        }
        return entry
    }

    fun set(key: String, entry: CacheEntry) {
        cache[key] = entry
    }

    fun clear(key: String) {
        cache.remove(key)
    }
}

class StreamSourcesDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "StreamSourcesDialogFragment"

        var pendingEpisode: ResultEpisode? = null
        var pendingResponse: LoadResponse? = null
        var pendingGenerator: VideoGenerator<*>? = null
        var pendingIndex: Int = 0
        var pendingSyncData: HashMap<String, String>? = null
        var pendingIsDownloadOnly: Boolean = false

        fun show(
            activity: FragmentActivity?,
            episode: ResultEpisode?,
            response: LoadResponse?,
            generator: VideoGenerator<*>?,
            index: Int,
            syncData: HashMap<String, String>?,
            isDownloadOnly: Boolean = false
        ) {
            if (activity == null || activity.isFinishing) return
            val fm = activity.supportFragmentManager
            if (fm.isStateSaved) return
            if (fm.findFragmentByTag(TAG) != null) return

            pendingEpisode = episode
            pendingResponse = response
            pendingGenerator = generator
            pendingIndex = index
            pendingSyncData = syncData
            pendingIsDownloadOnly = isDownloadOnly

            val dialog = StreamSourcesDialogFragment()
            dialog.show(fm, TAG)
        }
    }

    private var currentEpisode: ResultEpisode? = null
    private var originalEpisode: ResultEpisode? = null
    private var currentResponse: LoadResponse? = null
    private var currentGenerator: VideoGenerator<*>? = null
    private var currentIndex: Int = 0
    private var syncData: HashMap<String, String>? = null
    private var isDownloadOnly: Boolean = false

    private var scrapingJob: Job? = null
    private var updateJob: Job? = null
    private var isScrapingComplete: Boolean = false
    private var selectedFilter: String? = null // null = "All"
    private var currentCacheKey: String = ""

    private val activeScrapers = Collections.synchronizedSet(LinkedHashSet<String>())
    private val fetchingScrapers = ConcurrentHashMap.newKeySet<String>()
    private val providerLinks = ConcurrentHashMap<String, MutableList<ExtractorLink>>()
    private val allLoadedLinks = CopyOnWriteArrayList<ExtractorLink>()
    private val allLoadedSubs = CopyOnWriteArrayList<SubtitleData>()

    private var sourcesAdapter: StreamSourcesAdapter? = null
    private var loadingIndicator: ProgressBar? = null
    private var emptyView: LinearLayout? = null
    private var filterContainer: LinearLayout? = null
    private var scrapersContainer: LinearLayout? = null
    private var scrapersSection: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.DialogFullscreenPlayer)
        currentEpisode = pendingEpisode
        originalEpisode = pendingEpisode
        currentResponse = pendingResponse
        currentGenerator = pendingGenerator
        currentIndex = pendingIndex
        syncData = pendingSyncData
        isDownloadOnly = pendingIsDownloadOnly

        // Fallback for currentEpisode if null but currentResponse exists
        val resp = currentResponse
        if (currentEpisode == null && resp != null) {
            val dataUrl = (resp as? MovieLoadResponse)?.dataUrl?.ifBlank { null } ?: resp.url
            currentEpisode = buildResultEpisode(
                headerName = resp.name,
                name = resp.name,
                poster = resp.posterUrl,
                episode = 0,
                season = null,
                data = dataUrl,
                apiName = resp.apiName,
                id = dataUrl.hashCode(),
                index = 0,
                tvType = resp.type,
                parentId = dataUrl.hashCode()
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_stream_sources, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val backButton = view.findViewById<ImageButton>(R.id.sources_back_button)
        val refreshButton = view.findViewById<ImageButton>(R.id.sources_refresh_button)
        val posterView = view.findViewById<ImageView>(R.id.sources_poster)
        val titleView = view.findViewById<TextView>(R.id.sources_title)
        val subtitleView = view.findViewById<TextView>(R.id.sources_subtitle)
        val recyclerView = view.findViewById<RecyclerView>(R.id.sources_recycler_view)
        val retryButton = view.findViewById<Button>(R.id.sources_retry_button)

        loadingIndicator = view.findViewById(R.id.sources_loading_indicator)
        emptyView = view.findViewById(R.id.sources_empty_view)
        filterContainer = view.findViewById(R.id.sources_filter_container)
        scrapersContainer = view.findViewById(R.id.sources_scrapers_container)
        scrapersSection = view.findViewById(R.id.sources_scrapers_section)

        backButton?.setOnClickListener {
            dismissAllowingStateLoss()
        }

        refreshButton?.setOnClickListener {
            startScraping(forceRefresh = true)
        }

        retryButton?.setOnClickListener {
            startScraping(forceRefresh = true)
        }

        val ep = currentEpisode
        val resp = currentResponse

        val displayTitle = resp?.name ?: ep?.name ?: ""
        titleView?.text = displayTitle

        val season = ep?.season
        val episodeNum = ep?.episode
        if (season != null && episodeNum != null && season > 0) {
            val epName = ep.name
            val prefix = if (isDownloadOnly) "⬇️ Download • " else ""
            subtitleView?.text = if (!epName.isNullOrBlank() && epName != displayTitle) {
                "${prefix}Season $season • Episode $episodeNum: $epName"
            } else {
                "${prefix}Season $season • Episode $episodeNum"
            }
            subtitleView?.isVisible = true
        } else if (resp?.year != null) {
            val prefix = if (isDownloadOnly) "⬇️ Select source to download • " else ""
            subtitleView?.text = "$prefix${resp.year}"
            subtitleView?.isVisible = true
        } else {
            if (isDownloadOnly) {
                subtitleView?.text = "⬇️ Select source to download"
                subtitleView?.isVisible = true
            } else {
                subtitleView?.isVisible = false
            }
        }

        val posterUrl = ep?.poster ?: resp?.posterUrl
        if (!posterUrl.isNullOrBlank()) {
            posterView?.loadImage(posterUrl)
        }

        sourcesAdapter = StreamSourcesAdapter(
            isDownloadOnly = isDownloadOnly,
            onLinkClick = { selectedLink ->
                playSelectedLink(selectedLink)
            },
            onDownloadClick = { selectedLink ->
                downloadSelectedLink(selectedLink)
            }
        )
        recyclerView?.layoutManager = LinearLayoutManager(context)
        recyclerView?.adapter = sourcesAdapter
        recyclerView?.setHasFixedSize(false)

        startScraping(forceRefresh = false)
    }

    private fun startScraping(forceRefresh: Boolean = false) {
        scrapingJob?.cancel()
        updateJob?.cancel()
        isScrapingComplete = false
        loadingIndicator?.isVisible = true
        emptyView?.isVisible = false

        val rawEpisode = currentEpisode ?: return
        val resp = currentResponse
        val cacheKey = StreamSourcesCache.getCacheKey(rawEpisode, resp)
        currentCacheKey = cacheKey

        if (forceRefresh) {
            StreamSourcesCache.clear(cacheKey)
            providerLinks.clear()
            allLoadedLinks.clear()
            allLoadedSubs.clear()
            fetchingScrapers.clear()
            activeScrapers.clear()
            filterContainer?.removeAllViews()
            scrapersContainer?.removeAllViews()
            renderFilterChips()
            renderSourcesList()
        } else {
            // Check cache: if links were already fetched, display immediately without fetching again!
            val cached = StreamSourcesCache.get(cacheKey)
            if (cached != null && cached.allLoadedLinks.isNotEmpty()) {
                activeScrapers.clear()
                activeScrapers.addAll(cached.activeScrapers)
                providerLinks.clear()
                cached.providerLinks.forEach { (k, v) ->
                    providerLinks[k] = CopyOnWriteArrayList(v)
                }
                allLoadedLinks.clear()
                allLoadedLinks.addAll(cached.allLoadedLinks)
                allLoadedSubs.clear()
                allLoadedSubs.addAll(cached.allLoadedSubs)
                fetchingScrapers.clear()
                isScrapingComplete = true
                loadingIndicator?.isVisible = false
                renderActiveScrapersUi()
                renderFilterChips()
                renderSourcesList()
                return
            }
        }

        // Construct safe TmdbLink if raw data is not JSON or is missing title/year/tmdbID
        val parsedTmdbLink = tryParseJson<TmdbLink>(rawEpisode.data)
            ?: tryParseJson<TmdbLink>((resp as? MovieLoadResponse)?.dataUrl)

        val isAnime = parsedTmdbLink?.isAnime ?: (resp?.type == TvType.Anime || resp?.type == TvType.AnimeMovie)
        val isTv = (parsedTmdbLink?.season != null && parsedTmdbLink.episode != null) || ((rawEpisode.season ?: 0) > 0)

        val tmdbId = parsedTmdbLink?.tmdbID
            ?: Regex("""themoviedb\.org/(?:movie|tv)/(\d+)""").find(resp?.url ?: rawEpisode.data)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: resp?.syncData?.get("tmdb")?.toIntOrNull()
        val imdbId = (if (isTv) resp?.syncData?.get("imdb") ?: parsedTmdbLink?.imdbID else parsedTmdbLink?.imdbID ?: resp?.syncData?.get("imdb"))?.ifBlank { null }
        val movieTitle = (resp?.name?.ifBlank { null } ?: parsedTmdbLink?.movieName?.ifBlank { null } ?: rawEpisode.name)?.trim().orEmpty()
        val year = parsedTmdbLink?.year ?: resp?.year
        val season = if (isTv) (rawEpisode.season ?: parsedTmdbLink?.season ?: 1) else null
        val episodeNum = if (isTv) (if (rawEpisode.episode > 0) rawEpisode.episode else (parsedTmdbLink?.episode ?: 1)) else null

        val safeTmdbLink = TmdbLink(
            imdbID = imdbId,
            tmdbID = tmdbId,
            episode = episodeNum,
            season = season,
            movieName = movieTitle,
            year = year,
            isAnime = isAnime
        )

        val resolvedApiName = if (APIHolder.getApiFromNameNull(rawEpisode.apiName) == null) {
            APIHolder.allProviders.firstOrNull { it is CrossTmdbProvider }?.name ?: "JoyFlix Discover"
        } else {
            rawEpisode.apiName
        }

        val episode = rawEpisode.copy(
            apiName = resolvedApiName,
            data = safeTmdbLink.toJson()
        )
        currentEpisode = episode

        // Pre-discover scrapers based on media type (anime vs general, series vs movie)
        val initialScrapers = CrossTmdbProvider.getAvailableScrapers(isAnime, isTv)
        activeScrapers.addAll(initialScrapers)
        fetchingScrapers.addAll(initialScrapers)
        renderActiveScrapersUi()
        renderFilterChips()
        renderSourcesList()

        CrossTmdbProvider.scraperListener = object : CrossTmdbProvider.ScraperListener {
            override fun onScrapersDiscovered(scrapers: List<String>) {
                activeScrapers.addAll(scrapers)
                fetchingScrapers.addAll(scrapers)
                scheduleUiUpdate()
            }

            override fun onScraperStarted(scraper: String) {
                activeScrapers.add(scraper)
                fetchingScrapers.add(scraper)
                scheduleUiUpdate()
            }

            override fun onScraperFinished(scraper: String, linksCount: Int) {
                fetchingScrapers.remove(scraper)
                scheduleUiUpdate()
            }
        }

        val tempGenerator = RepoLinkGenerator(listOf(episode), page = resp)

        scrapingJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                tempGenerator.generateLinks(
                    clearCache = true,
                    sourceTypes = LOADTYPE_ALL,
                    callback = { (link, _) ->
                        if (link != null && isValidLink(link)) {
                            handleIncomingLink(link)
                        }
                    },
                    subtitleCallback = { sub ->
                        allLoadedSubs.add(sub)
                    },
                    offset = 0,
                    isCasting = false
                )
            } catch (e: Exception) {
                logError(e)
            } finally {
                withContext(Dispatchers.Main) {
                    isScrapingComplete = true
                    loadingIndicator?.isVisible = false
                    fetchingScrapers.clear()
                    updateJob?.cancel()
                    saveCurrentCache()
                    renderFilterChips()
                    renderSourcesList()
                }
            }
        }
    }

    private fun isValidLink(link: ExtractorLink): Boolean {
        if (link.url.isBlank()) return false
        if (com.lagradost.cloudstream3.utils.DeadLinkManager.isDead(link.url)) return false
        val urlLower = link.url.lowercase()
        val nameLower = link.name.lowercase()
        val sourceLower = link.source.lowercase()

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

    private fun handleIncomingLink(link: ExtractorLink) {
        if (allLoadedLinks.any { it.url == link.url }) return
        allLoadedLinks.add(link)

        val provider = link.source.ifBlank { "Stream" }
        val list = providerLinks.getOrPut(provider) { CopyOnWriteArrayList() }
        list.add(link)
        fetchingScrapers.remove(provider)

        saveCurrentCache()
        scheduleUiUpdate()
    }

    private fun saveCurrentCache() {
        val key = currentCacheKey.ifBlank {
            StreamSourcesCache.getCacheKey(currentEpisode, currentResponse)
        }
        StreamSourcesCache.set(
            key,
            StreamSourcesCache.CacheEntry(
                activeScrapers = synchronized(activeScrapers) { activeScrapers.toList() },
                providerLinks = providerLinks.mapValues { it.value.toList() },
                allLoadedLinks = allLoadedLinks.toList(),
                allLoadedSubs = allLoadedSubs.toList()
            )
        )
    }

    private fun scheduleUiUpdate(delayMs: Long = 120L) {
        if (!isAdded || isDetached) return
        if (updateJob?.isActive == true) return
        updateJob = lifecycleScope.launch(Dispatchers.Main) {
            delay(delayMs)
            if (!isAdded || isDetached) return@launch
            renderActiveScrapersUi()
            renderFilterChips()
            renderSourcesList()
        }
    }

    @SuppressLint("InflateParams")
    private fun renderActiveScrapersUi() {
        val container = scrapersContainer ?: return
        val ctx = context ?: return
        val inflater = LayoutInflater.from(ctx)

        if (activeScrapers.isEmpty()) {
            scrapersSection?.isVisible = false
            return
        }
        scrapersSection?.isVisible = true

        val existingTags = mutableSetOf<String>()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) as? TextView ?: continue
            val tag = child.tag as? String ?: continue
            existingTags.add(tag)
        }

        val scrapersCopy = synchronized(activeScrapers) { activeScrapers.toList() }
        for (scraper in scrapersCopy) {
            if (!existingTags.contains(scraper)) {
                val chip = inflater.inflate(R.layout.item_active_scraper_chip, container, false) as TextView
                chip.text = scraper
                chip.tag = scraper
                container.addView(chip)
                existingTags.add(scraper)
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun renderFilterChips() {
        val container = filterContainer ?: return
        val ctx = context ?: return
        val inflater = LayoutInflater.from(ctx)

        val providersWithLinks = providerLinks.filter { it.value.isNotEmpty() }.keys
        val scrapersSnapshot = synchronized(activeScrapers) { activeScrapers.toList() }
        val filterOptions = providersWithLinks.ifEmpty { scrapersSnapshot }

        val requiredChips = mutableListOf("All")
        requiredChips.addAll(filterOptions)

        val existingChips = mutableMapOf<String, TextView>()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) as? TextView ?: continue
            val tag = child.tag as? String ?: continue
            existingChips[tag] = child
        }

        // Add chips that don't exist yet
        for (name in requiredChips) {
            if (!existingChips.containsKey(name)) {
                val chip = inflater.inflate(R.layout.item_stream_filter_chip, container, false) as TextView
                chip.text = name
                chip.tag = name
                chip.setOnClickListener {
                    selectedFilter = if (name == "All" || selectedFilter == name) null else name
                    renderFilterChips()
                    renderSourcesList()
                }
                container.addView(chip)
                existingChips[name] = chip
            }
        }

        // Update styling and visibility without removing existing views!
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) as? TextView ?: continue
            val tag = child.tag as? String ?: continue
            val isSelected = (selectedFilter == null && tag == "All") || (selectedFilter == tag)
            styleFilterChip(child, isSelected)
            child.isVisible = requiredChips.contains(tag)
        }
    }

    private fun styleFilterChip(chip: TextView, isSelected: Boolean) {
        if (isSelected) {
            chip.setBackgroundResource(R.drawable.bg_filter_chip_selected)
            chip.setTextColor(0xFF000000.toInt())
        } else {
            chip.setBackgroundResource(R.drawable.bg_filter_chip_unselected)
            chip.setTextColor(0xFFFFFFFF.toInt())
        }
    }

    private fun renderSourcesList() {
        val items = mutableListOf<StreamSourceItem>()

        val displayedProviders = if (selectedFilter != null) {
            listOf(selectedFilter!!)
        } else {
            val set = linkedSetOf<String>()
            // Providers that found links
            set.addAll(providerLinks.filter { it.value.isNotEmpty() }.keys)
            // While still scraping: show scrapers currently fetching
            if (!isScrapingComplete) {
                set.addAll(fetchingScrapers)
            }
            // Once scraping completes: providers without links are omitted!
            set.toList()
        }

        for (provider in displayedProviders) {
            val links = providerLinks[provider] ?: emptyList()
            val isFetching = fetchingScrapers.contains(provider) && !isScrapingComplete

            if (links.isNotEmpty() || isFetching) {
                items.add(StreamSourceItem.Header(provider, isFetching))
                for (link in links) {
                    items.add(StreamSourceItem.Link(link, provider))
                }
            }
        }

        sourcesAdapter?.submitList(items)
        emptyView?.isVisible = isScrapingComplete && allLoadedLinks.isEmpty()
    }

    private fun playSelectedLink(selectedLink: ExtractorLink) {
        val act = activity ?: return
        val episode = currentEpisode ?: return
        val generator = currentGenerator ?: RepoLinkGenerator(listOf(episode), page = currentResponse)

        val tmdbId = currentResponse?.getTMDbId()?.toIntOrNull()
            ?: episode.data.let { d ->
                Regex(""""tmdbID"\s*:\s*(\d+)""").find(d)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("tmdbID=(\\d+)").find(d)?.groupValues?.get(1)?.toIntOrNull()
            }
        val mediaTitle = currentResponse?.name ?: episode.headerName ?: episode.name
        val detectedDub = com.lagradost.cloudstream3.utils.MediaSourcePreferenceHelper.isDubIndicator(selectedLink.name)
            ?: com.lagradost.cloudstream3.utils.MediaSourcePreferenceHelper.isDubIndicator(selectedLink.source)
        com.lagradost.cloudstream3.utils.MediaSourcePreferenceHelper.savePreferredSource(
            title = mediaTitle,
            id = tmdbId,
            scraper = selectedLink.source,
            serverName = selectedLink.name,
            explicitDub = detectedDub
        )

        if (detectedDub == true) {
            com.lagradost.cloudstream3.ui.player.CS3IPlayer.preferredAudioTrackLanguage = "en"
        } else if (detectedDub == false) {
            com.lagradost.cloudstream3.ui.player.CS3IPlayer.preferredAudioTrackLanguage = "ja"
        }

        // 1. Populate RepoLinkGenerator.cache with selected link first!
        synchronized(RepoLinkGenerator.cache) {
            val keysToCache = setOf(
                episode.apiName to episode.id,
                "JoyFlix Discover" to episode.id,
                "TMDB" to episode.id
            )
            for (key in keysToCache) {
                val cacheItem = RepoLinkGenerator.cache.getOrPut(key) {
                    Cache(mutableSetOf(), mutableSetOf(), unixTime, true)
                }
                val orderedLinks = linkedSetOf<ExtractorLink>()
                orderedLinks.add(selectedLink)
                orderedLinks.addAll(allLoadedLinks)

                cacheItem.linkCache.clear()
                cacheItem.linkCache.addAll(orderedLinks)
                cacheItem.subtitleCache.clear()
                cacheItem.subtitleCache.addAll(allLoadedSubs)
                cacheItem.saturated = true
                cacheItem.lastCachedTimestamp = unixTime
            }
        }

        // 2. Dismiss dialog
        dismissAllowingStateLoss()

        // If in Watch Together and isHost: broadcast this source immediately!
        if (com.lagradost.cloudstream3.utils.WatchTogetherManager.isInRoom &&
            com.lagradost.cloudstream3.utils.WatchTogetherManager.isHost
        ) {
            com.lagradost.cloudstream3.utils.WatchTogetherManager.broadcastSource(
                streamUrl = selectedLink.url,
                mediaUrl = currentResponse?.url ?: episode.data,
                apiName = currentResponse?.apiName ?: episode.apiName,
                episodeId = episode.id,
                title = currentResponse?.name ?: episode.name,
                episode = episode.episode,
                season = episode.season,
                poster = currentResponse?.posterUrl ?: episode.poster,
                tvType = currentResponse?.type?.name ?: episode.tvType?.name,
                episodeIndex = currentIndex
            )
        }

        // 3. If player is already active, switch link directly in-player!
        val currentActivity = act
        if (currentActivity is androidx.fragment.app.FragmentActivity) {
            val navHost = currentActivity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
            val currentFrag = navHost?.childFragmentManager?.fragments?.filterIsInstance<GeneratorPlayer>()?.lastOrNull()
                ?: currentActivity.supportFragmentManager.fragments.filterIsInstance<GeneratorPlayer>().lastOrNull()
            if (currentFrag != null) {
                currentFrag.selectSpecificLink(selectedLink)
                return
            }
        }

        // 4. Otherwise start generator player!
        act.navigate(
            R.id.global_to_navigation_player,
            GeneratorPlayer.newInstance(
                generator,
                currentIndex,
                syncData
            )
        )
    }

    private fun downloadSelectedLink(selectedLink: ExtractorLink) {
        val act = activity ?: return
        val episode = originalEpisode ?: currentEpisode ?: return
        val response = currentResponse

        saveCurrentCache()

        val isMovie = response?.isMovie() ?: (episode.tvType == TvType.Movie || episode.tvType == TvType.AnimeMovie)
        val resultName = response?.name ?: episode.name ?: ""
        val resultType = response?.type ?: episode.tvType ?: TvType.Movie
        val resultPoster = response?.posterUrl ?: episode.poster
        val apiName = response?.apiName ?: episode.apiName
        val resultId = response?.getId() ?: episode.parentId ?: episode.id
        val resultUrl = response?.url ?: episode.data

        DownloadQueueManager.addToQueue(
            DownloadObjects.DownloadQueueItem(
                episode = episode,
                isMovie = isMovie,
                resultName = resultName,
                resultType = resultType,
                resultPoster = resultPoster,
                apiName = apiName,
                resultId = resultId,
                resultUrl = resultUrl,
                links = listOf(selectedLink),
                subs = allLoadedSubs.toList().ifEmpty { null },
            ).toWrapper()
        )

        CommonActivity.showToast(act, R.string.download_started, Toast.LENGTH_SHORT)
        dismissAllowingStateLoss()
    }

    override fun onDestroy() {
        super.onDestroy()
        scrapingJob?.cancel()
        updateJob?.cancel()
        CrossTmdbProvider.scraperListener = null
        pendingEpisode = null
        pendingResponse = null
        pendingGenerator = null
        pendingSyncData = null
        pendingIsDownloadOnly = false
    }
}

sealed class StreamSourceItem {
    data class Header(val providerName: String, val isFetching: Boolean) : StreamSourceItem()
    data class Link(val link: ExtractorLink, val providerName: String) : StreamSourceItem()
}

class StreamSourceDiffCallback : DiffUtil.ItemCallback<StreamSourceItem>() {
    override fun areItemsTheSame(oldItem: StreamSourceItem, newItem: StreamSourceItem): Boolean {
        return when {
            oldItem is StreamSourceItem.Header && newItem is StreamSourceItem.Header ->
                oldItem.providerName == newItem.providerName
            oldItem is StreamSourceItem.Link && newItem is StreamSourceItem.Link ->
                oldItem.link.url == newItem.link.url
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: StreamSourceItem, newItem: StreamSourceItem): Boolean {
        return oldItem == newItem
    }
}

class StreamSourcesAdapter(
    private val isDownloadOnly: Boolean,
    private val onLinkClick: (ExtractorLink) -> Unit,
    private val onDownloadClick: (ExtractorLink) -> Unit
) : ListAdapter<StreamSourceItem, RecyclerView.ViewHolder>(StreamSourceDiffCallback()) {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_LINK = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is StreamSourceItem.Header -> TYPE_HEADER
            is StreamSourceItem.Link -> TYPE_LINK
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_stream_source_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_stream_source_card, parent, false)
            LinkViewHolder(view, isDownloadOnly, onLinkClick, onDownloadClick)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is StreamSourceItem.Header -> (holder as HeaderViewHolder).bind(item)
            is StreamSourceItem.Link -> (holder as LinkViewHolder).bind(item)
        }
    }

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.header_provider_title)
        private val status: TextView = itemView.findViewById(R.id.header_provider_status)

        fun bind(header: StreamSourceItem.Header) {
            title.text = header.providerName
            status.isVisible = header.isFetching
            status.text = if (header.isFetching) "Fetching..." else ""
        }
    }

    class LinkViewHolder(
        itemView: View,
        private val isDownloadOnly: Boolean,
        private val onLinkClick: (ExtractorLink) -> Unit,
        private val onDownloadClick: (ExtractorLink) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.source_name)
        private val quality: TextView = itemView.findViewById(R.id.source_quality)
        private val badge: TextView = itemView.findViewById(R.id.source_badge)
        private val downloadBtn: ImageView = itemView.findViewById(R.id.source_download_btn)

        fun bind(linkItem: StreamSourceItem.Link) {
            val link = linkItem.link
            val cleanName = link.name
                .replace("[${linkItem.providerName}]", "")
                .trim()
                .ifBlank { linkItem.providerName }

            title.text = cleanName

            val qualityStr = Qualities.getStringByInt(link.quality)
            val provider = linkItem.providerName
            quality.text = if (qualityStr.isNotBlank() && qualityStr != "Unknown") {
                "$qualityStr • $provider"
            } else {
                provider
            }

            if (qualityStr.isNotBlank() && qualityStr != "Unknown") {
                badge.text = qualityStr
                badge.isVisible = true
            } else {
                badge.isVisible = false
            }

            if (isDownloadOnly) {
                downloadBtn.imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                itemView.setOnClickListener {
                    onDownloadClick(link)
                }
                downloadBtn.setOnClickListener {
                    onDownloadClick(link)
                }
            } else {
                downloadBtn.imageTintList = android.content.res.ColorStateList.valueOf(0xFFA5A5AA.toInt())
                itemView.setOnClickListener {
                    onLinkClick(link)
                }
                downloadBtn.setOnClickListener {
                    onDownloadClick(link)
                }
            }
        }
    }
}
