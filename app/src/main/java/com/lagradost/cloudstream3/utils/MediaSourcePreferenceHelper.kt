package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages scraper, server, and Dub/Sub language preference persistence across episodes and sessions.
 * Remembers the last selected scraper/source and audio language (e.g. English vs Japanese) so
 * next episodes and re-opening the app automatically play from the exact same source & language.
 */
object MediaSourcePreferenceHelper {
    private const val PREF_SOURCE_FOLDER = "preferred_media_source_v1"

    data class SourcePref(
        val scraper: String?,
        val serverName: String?,
        val isDub: Boolean? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    // In-memory cache for fast, zero-delay lookups across episode transitions
    private val memoryCache = ConcurrentHashMap<String, SourcePref>()

    fun normalizeKey(title: String?, id: Int?): String? {
        if (id != null && id > 0) return "id_$id"
        if (!title.isNullOrBlank()) {
            val clean = title.lowercase().trim().replace(Regex("""[^a-z0-9]"""), "")
            if (clean.isNotBlank()) return "title_$clean"
        }
        return null
    }

    /**
     * Determines whether text indicates English/Dub or Japanese/Sub.
     * Returns true for Dub/English, false for Sub/Japanese, or null if neutral/unknown.
     */
    fun isDubIndicator(text: String?): Boolean? {
        if (text.isNullOrBlank()) return null
        val lower = text.lowercase()
        val hasDub = lower.contains("dub") || lower.contains("english") || lower.contains("(eng)") || lower.contains("[eng]")
        val hasSub = lower.contains("sub") || lower.contains("japanese") || lower.contains("(jap)") || lower.contains("[jap]")
        return when {
            hasDub && !hasSub -> true
            hasSub && !hasDub -> false
            hasDub && hasSub -> {
                if (lower.contains("(dub)") || lower.contains("[dub]")) true
                else if (lower.contains("(sub)") || lower.contains("[sub]")) false
                else null
            }
            else -> null
        }
    }

    /**
     * Extracts actual scraper name (e.g. "FlixHQ" from "[FlixHQ] Server 1" or source property).
     * Prevents storing meta-provider names like "JoyFlix Discover" or "TMDB".
     */
    fun extractScraperName(scraper: String?, serverName: String?): String? {
        if (!scraper.isNullOrBlank() &&
            !scraper.equals("JoyFlix Discover", ignoreCase = true) &&
            !scraper.equals("TMDB", ignoreCase = true) &&
            !scraper.equals("Universal Embeds", ignoreCase = true)
        ) {
            return scraper.trim()
        }
        if (!serverName.isNullOrBlank()) {
            val bracketMatch = Regex("""\[(.*?)\]""").find(serverName)
            if (bracketMatch != null) {
                val inside = bracketMatch.groupValues[1].trim()
                if (inside.isNotBlank() &&
                    !inside.equals("JoyFlix Discover", ignoreCase = true) &&
                    !inside.equals("TMDB", ignoreCase = true)
                ) {
                    return inside
                }
            }
        }
        return scraper?.trim()?.takeIf {
            !it.equals("JoyFlix Discover", ignoreCase = true) && !it.equals("TMDB", ignoreCase = true)
        }
    }

    /**
     * Save the user's selected scraper, server, and language/dub preference for this media.
     */
    fun savePreferredSource(
        title: String?,
        id: Int?,
        scraper: String?,
        serverName: String?,
        explicitDub: Boolean? = null
    ) {
        val cleanScraper = extractScraperName(scraper, serverName)
        val cleanServer = serverName?.trim()
        val detectedDub = explicitDub ?: isDubIndicator(cleanServer) ?: isDubIndicator(cleanScraper)

        if (cleanScraper.isNullOrBlank() && cleanServer.isNullOrBlank() && detectedDub == null) return

        val pref = SourcePref(cleanScraper, cleanServer, detectedDub)

        val keys = linkedSetOf<String>()
        if (id != null && id > 0) keys.add("id_$id")
        normalizeKey(title, null)?.let { keys.add(it) }

        keys.forEach { key ->
            memoryCache[key] = pref
            try {
                if (!cleanScraper.isNullOrBlank()) {
                    setKey("$currentAccount/$PREF_SOURCE_FOLDER", "${key}_scraper", cleanScraper)
                }
                if (!cleanServer.isNullOrBlank()) {
                    setKey("$currentAccount/$PREF_SOURCE_FOLDER", "${key}_server", cleanServer)
                }
                if (detectedDub != null) {
                    setKey("$currentAccount/$PREF_SOURCE_FOLDER", "${key}_dub", if (detectedDub) "dub" else "sub")
                }
            } catch (_: Throwable) {
            }
        }

        // Also persist global anime dub preference
        if (detectedDub != null) {
            try {
                setKey("$currentAccount/$PREF_SOURCE_FOLDER", "global_anime_dub", if (detectedDub) "dub" else "sub")
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Get the preferred scraper name (e.g. "FlixHQ", "SuperStream") for this media.
     */
    fun getPreferredScraper(title: String?, id: Int?): String? {
        val keys = linkedSetOf<String>()
        if (id != null && id > 0) keys.add("id_$id")
        normalizeKey(title, null)?.let { keys.add(it) }

        for (key in keys) {
            memoryCache[key]?.scraper?.let { return it }
            try {
                getKey<String>("$currentAccount/$PREF_SOURCE_FOLDER", "${key}_scraper")?.let { scraper ->
                    if (scraper.isNotBlank()) {
                        memoryCache[key] = SourcePref(scraper, memoryCache[key]?.serverName, memoryCache[key]?.isDub)
                        return scraper
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /**
     * Get the preferred server name (e.g. "[FlixHQ] Server 1", "UpCloud") for this media.
     */
    fun getPreferredServer(title: String?, id: Int?): String? {
        val keys = linkedSetOf<String>()
        if (id != null && id > 0) keys.add("id_$id")
        normalizeKey(title, null)?.let { keys.add(it) }

        for (key in keys) {
            memoryCache[key]?.serverName?.let { return it }
            try {
                getKey<String>("$currentAccount/$PREF_SOURCE_FOLDER", "${key}_server")?.let { server ->
                    if (server.isNotBlank()) {
                        memoryCache[key] = SourcePref(memoryCache[key]?.scraper, server, memoryCache[key]?.isDub)
                        return server
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /**
     * Get the preferred Dub/Sub language (true = Dub/English, false = Sub/Japanese) for this media or globally.
     */
    fun getPreferredDub(title: String?, id: Int?): Boolean? {
        val keys = linkedSetOf<String>()
        if (id != null && id > 0) keys.add("id_$id")
        normalizeKey(title, null)?.let { keys.add(it) }

        for (key in keys) {
            memoryCache[key]?.isDub?.let { return it }
            try {
                getKey<String>("$currentAccount/$PREF_SOURCE_FOLDER", "${key}_dub")?.let { str ->
                    val isDub = str.equals("dub", ignoreCase = true)
                    memoryCache[key] = SourcePref(memoryCache[key]?.scraper, memoryCache[key]?.serverName, isDub)
                    return isDub
                }
            } catch (_: Throwable) {
            }
        }

        // Global fallback if no anime-specific preference yet
        try {
            getKey<String>("$currentAccount/$PREF_SOURCE_FOLDER", "global_anime_dub")?.let { str ->
                return str.equals("dub", ignoreCase = true)
            }
        } catch (_: Throwable) {
        }

        return null
    }

    /**
     * Computes matching score between a link and the preferred scraper/server/language.
     * Enforces language consistency: if user chose English/Dub, Japanese/Sub links score 0.
     */
    fun matchScore(
        link: Pair<ExtractorLink?, ExtractorUri?>,
        preferredScraper: String?,
        preferredServer: String?,
        preferredDub: Boolean? = null
    ): Int {
        val ext = link.first
        val uri = link.second
        val src = ext?.source
        val sName = ext?.name ?: uri?.name

        // 1. Language Hard Filter: Enforce Dub vs Sub consistency
        val linkDub = isDubIndicator(sName) ?: isDubIndicator(src)
        if (preferredDub != null && linkDub != null) {
            if (preferredDub && !linkDub) {
                // User prefers English/Dub, but this link is Japanese/Sub -> REJECT completely
                return 0
            }
            if (!preferredDub && linkDub) {
                // User prefers Japanese/Sub, but this link is English/Dub -> REJECT completely
                return 0
            }
        }

        // 2. Scraper Matching
        val matchesScraper = !preferredScraper.isNullOrBlank() && (
            src.equals(preferredScraper, ignoreCase = true) ||
            sName?.contains(preferredScraper, ignoreCase = true) == true ||
            sName?.contains("[$preferredScraper]", ignoreCase = true) == true
        )

        // 3. Server Matching
        val matchesServer = !preferredServer.isNullOrBlank() && (
            sName.equals(preferredServer, ignoreCase = true) ||
            (ext?.name != null && preferredServer.contains(ext.name, ignoreCase = true))
        )

        var score = when {
            matchesScraper && matchesServer -> 100
            matchesScraper -> 80
            matchesServer -> 50
            else -> 0
        }

        // Language matching bonus
        if (score > 0 && preferredDub != null && linkDub == preferredDub) {
            score += 25
        } else if (score == 0 && preferredDub != null && linkDub == preferredDub) {
            score = 30
        }

        return score
    }

    /**
     * Helper to test if a link matches the user's preferred scraper, server, and language.
     */
    fun matchesPreferred(
        link: Pair<ExtractorLink?, ExtractorUri?>,
        preferredScraper: String?,
        preferredServer: String?,
        preferredDub: Boolean? = null
    ): Boolean {
        return matchScore(link, preferredScraper, preferredServer, preferredDub) > 0
    }
}
