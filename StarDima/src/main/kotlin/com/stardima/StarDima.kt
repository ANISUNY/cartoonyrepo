@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "NewApi")

package com.stardima

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class StarDima : MainAPI() {
    override var mainUrl = "https://www.stardima.com"
    override var name = "StarDima"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries, TvType.Movie)

    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val hwUrl = "https://hyperwatching.com"
    private var dbg = ""

    private fun hdrs(referer: String = "$mainUrl/") = mapOf(
        "User-Agent"      to desktopUa,
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Referer"         to referer,
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    private fun ajaxHdrs(referer: String = "$mainUrl/") = hdrs(referer) + mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Accept"           to "application/json, */*; q=0.01"
    )

    override val mainPage = mainPageOf(
        ""           to "الرئيسية",
        "newrelases" to "المضاف حديثاً",
        "mosalsalat" to "مسلسلات",
        "aflam"      to "أفلام"
    )

    private fun isMovie(url: String)  = url.contains("/movie/")
    private fun isTvShow(url: String) = url.contains("/tvshow/")
    private fun isImageAsset(url: String) =
        url.contains(".svg", true) || url.contains(".png", true) ||
            url.contains(".jpg", true) || url.contains(".jpeg", true) || url.contains(".webp", true)

    private fun isPlayableCandidate(url: String): Boolean {
        if (!url.startsWith("http")) return false
        if (isImageAsset(url)) return false
        // Only accept direct media files as raw fallback candidates.
        if (url.contains(".m3u8", true) || url.contains(".mp4", true)) return true
        return false
    }

    private fun isSafeFallbackUrl(url: String): Boolean {
        return isPlayableCandidate(url) || isExtractorHost(url) || isLikelyEmbedUrl(url)
    }

    private fun isExtractorHost(url: String): Boolean {
        if (!url.startsWith("http")) return false
        return Regex(
            """https?://(?:www\.)?(?:lulustream|uqload|krakenfiles|streamhg|earnvids|goodstream|darkibox|streamwish|vidhide|filelions|doodstream|streamtape|mixdrop|upstream|voe|strema\.top)[^/\s]*""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(url)
    }

    private fun isLikelyEmbedUrl(url: String): Boolean {
        if (!url.startsWith("http")) return false
        if (isImageAsset(url)) return false
        val lowered = url.lowercase()
        // Keep this narrow: allow common embed/player URL shapes used by supported extractors.
        return lowered.contains("/embed/") ||
            lowered.contains("/e/") ||
            lowered.contains("player") ||
            lowered.contains("/watch/")
    }

    private fun normalizeContentUrl(rawUrl: String): String {
        var url = rawUrl.substringBefore("#").substringBefore("?").trim().trimEnd('/')
        // Search pages often expose episode/play links; normalize back to the parent show/movie page.
        if ((url.contains("/tvshow/") || url.contains("/movie/")) && url.contains("/play/")) {
            url = url.substringBefore("/play/").trimEnd('/')
        }
        return url
    }

    /** Safe og:title, never returns login/modal text */
    private fun pageTitle(doc: Document): String {
        val og = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        if (!og.isNullOrBlank()
            && !og.contains("تسجيل")
            && !og.contains("ستارديما | STARDIMA")) return og
        return doc.title().trim()
            .substringBefore(" - ستارديما")
            .substringBefore(" | ستارديما")
            .substringBefore(" - STARDIMA")
            .substringBefore(" | STARDIMA")
            .trim()
            .ifBlank { "StarDima" }
    }

    // ─── parseCards ───────────────────────────────────────────────────────────

    /**
     * Extracts show/movie cards from any stardima page.
     * Strategy: find every anchor whose href is a /tvshow/ or /movie/ URL,
     * then try multiple strategies to get the title.
     */
    private fun parseCards(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen    = mutableSetOf<String>()

        for (a in doc.select("a[href]")) {
            val href = normalizeContentUrl(a.attr("abs:href"))
            if (href.isBlank() || !href.startsWith(mainUrl)) continue
            if (!isTvShow(href) && !isMovie(href)) continue
            if (!seen.add(href))                    continue

            var title : String? = null
            var poster: String? = null

            // 1. title attribute on the link itself
            val attrTitle = a.attr("title").trim()
            if (attrTitle.isNotBlank()) title = attrTitle

            // 2. Inside the anchor: img alt → poster
            val imgInside = a.selectFirst("img")
            if (imgInside != null) {
                if (title == null) {
                    val alt = imgInside.attr("alt").trim()
                    if (alt.isNotBlank()) title = alt
                }
                if (poster == null) {
                    poster = imgInside.attr("abs:src").ifBlank { null }
                        ?: imgInside.attr("abs:data-src").ifBlank { null }
                        ?: imgInside.attr("data-src").ifBlank { null }
                }
            }

            // 3. Walk up the DOM (up to 8 levels) searching for title/name elements
            if (title == null || poster == null) {
                var el: Element? = a.parent()
                var depth = 0
                while (el != null && depth < 8) {
                    // Title selectors in priority order
                    if (title == null) {
                        title = el.selectFirst(
                            ".st-title, .title, .name, [class*=title], [class*=name], h2, h3, h4"
                        )?.text()?.trim()?.ifBlank { null }
                    }
                    // Poster: any img we haven't grabbed yet
                    if (poster == null) {
                        val img = el.selectFirst("img[src], img[data-src]")
                        if (img != null) {
                            poster = img.attr("abs:src").ifBlank { null }
                                ?: img.attr("abs:data-src").ifBlank { null }
                                ?: img.attr("data-src").ifBlank { null }
                        }
                    }
                    if (title != null && poster != null) break
                    el = el.parent()
                    depth++
                }
            }

            // 4. Previous siblings
            if (title == null) {
                var sib = a.previousElementSibling()
                while (sib != null) {
                    val tag = sib.tagName()
                    if (tag in listOf("h2", "h3", "h4") && sib.text().isNotBlank()) {
                        title = sib.text().trim(); break
                    }
                    val t = sib.selectFirst(".st-title, .title, .name, [class*=title]")
                        ?.text()?.trim()?.ifBlank { null }
                    if (t != null) { title = t; break }
                    sib = sib.previousElementSibling()
                }
            }

            // 5. Fallback: link own text (if not a "watch" button)
            if (title == null) {
                val txt = a.text().trim()
                if (txt.isNotBlank() && !txt.contains("شاهد") && !txt.contains("Watch")) title = txt
            }

            val cleanTitle = title?.replace(Regex("\\s+"), " ")?.trim()
            if (cleanTitle.isNullOrBlank()) continue

            val type = when {
                isMovie(href)  -> TvType.Movie
                isTvShow(href) -> TvType.TvSeries
                else           -> TvType.Anime
            }
            results.add(newAnimeSearchResponse(cleanTitle, href, type) {
                posterUrl = poster
            })
        }
        return results
    }

    // ─── main/search ──────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val key = request.data
        val url = when {
            key.isBlank() -> mainUrl
            page == 1     -> "$mainUrl/$key"
            else          -> "$mainUrl/$key?page=$page"
        }
        val doc = try {
            app.get(url, headers = hdrs()).document
        } catch (t: Throwable) {
            Log.e("StarDima", "getMainPage: ${t.message}")
            return newHomePageResponse(request.name, emptyList())
        }
        val items  = parseCards(doc)
        val hasMore = key.isNotBlank() && items.isNotEmpty() && page < 50
        return newHomePageResponse(request.name, items, hasNext = hasMore)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().ifBlank { return emptyList() }
        return try {
            // Site currently uses ?query=... ; keep ?q=... fallback for older deployments.
            val primary = app.get(
                "$mainUrl/search",
                params  = mapOf("query" to q),
                headers = hdrs()
            ).document
            val first = parseCards(primary)
            if (first.isNotEmpty()) return first

            val fallback = app.get(
                "$mainUrl/search",
                params  = mapOf("q" to q),
                headers = hdrs()
            ).document
            parseCards(fallback)
        } catch (t: Throwable) {
            Log.e("StarDima", "search: ${t.message}")
            emptyList()
        }
    }

    // ─── Season ID extraction ─────────────────────────────────────────────────

    /**
     * Returns ALL season IDs found on a player page, in order.
     * Confirmed live selectors (2026-03):
     *   - ul#episodes-list-container[data-initial-season-id]  → primary season
     *   - a.season-item[data-season-id]                       → other seasons
     * Generic fallbacks retained for resilience.
     */
    private fun extractAllSeasonIds(doc: Document): List<String> {
        val ids  = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        // 1. CONFIRMED: ul#episodes-list-container data-initial-season-id
        doc.selectFirst("ul#episodes-list-container[data-initial-season-id]")
            ?.attr("data-initial-season-id")?.trim()
            ?.takeIf { it.isNotBlank() && seen.add(it) }
            ?.let { ids.add(it) }

        // 2. CONFIRMED: a.season-item[data-season-id]
        for (el in doc.select("a.season-item[data-season-id]")) {
            val sid = el.attr("data-season-id").trim()
            if (sid.isNotBlank() && seen.add(sid)) ids.add(sid)
        }

        // 3. Generic fallback: any [data-initial-season-id]
        doc.selectFirst("[data-initial-season-id]")
            ?.attr("data-initial-season-id")?.trim()
            ?.takeIf { it.isNotBlank() && seen.add(it) }
            ?.let { ids.add(it) }

        // 4. Generic fallback: any [data-season-id]
        for (el in doc.select("[data-season-id]")) {
            val sid = el.attr("data-season-id").trim()
            if (sid.isNotBlank() && seen.add(sid)) ids.add(sid)
        }

        // 5. Scan entire HTML for /series/season/{id} patterns
        val htmlText = doc.html()
        val seasonUrlPattern = Regex("""/series/season/(\d+)""")
        for (m in seasonUrlPattern.findAll(htmlText)) {
            val sid = m.groupValues[1]
            if (seen.add(sid)) ids.add(sid)
        }

        // 6. JS variable patterns
        val jsPatterns = listOf(
            Regex("""['"_]?season[_\-]?[Ii]d['"]?\s*[:=]\s*['"]?(\d+)"""),
            Regex("""currentSeason\s*[:=]\s*['"]?(\d+)"""),
            Regex("""activeSeason\s*[:=]\s*['"]?(\d+)""")
        )
        for (script in doc.select("script")) {
            val src = script.html()
            for (pat in jsPatterns) {
                for (m in pat.findAll(src)) {
                    val sid = m.groupValues[1]
                    if (sid.isNotBlank() && sid.length <= 10 && seen.add(sid)) ids.add(sid)
                }
            }
        }

        return ids
    }

    // ─── fetch episodes from /series/season/{id} API ──────────────────────────

    private suspend fun fetchSeasonEpisodes(seasonId: String, showPoster: String?): List<Episode> {
        return try {
            val text = app.get(
                "$mainUrl/series/season/$seasonId",
                headers = ajaxHdrs("$mainUrl/")
            ).text

            if (!text.trimStart().startsWith("{") && !text.trimStart().startsWith("["))
                return emptyList()

            val json = org.json.JSONObject(text)
            val arr  = json.optJSONArray("episodes") ?: return emptyList()
            data class RawEpisode(val watchUrl: String, val title: String, val parsedNum: Int?, val idx: Int)
            val numRegex = Regex("""(\d{1,4})\s*$""")

            val raw = (0 until arr.length()).mapNotNull { i ->
                val ep = arr.getJSONObject(i)
                val watchUrl = ep.optString("watch_url", "").trim()
                if (watchUrl.isBlank()) return@mapNotNull null
                val title = ep.optString("title", "الحلقة ${i + 1}").trim().ifBlank { "الحلقة ${i + 1}" }
                val titleNum = numRegex.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val apiNum = ep.optInt("episode_number", -1).takeIf { it > 0 }
                RawEpisode(watchUrl, title, titleNum ?: apiNum, i)
            }.sortedWith(compareBy<RawEpisode>({ it.parsedNum ?: Int.MAX_VALUE }, { it.idx }))

            raw.mapIndexed { index, ep ->
                val displayNum = index + 1
                newEpisode(ep.watchUrl) {
                    name      = ep.title
                    episode   = displayNum // keep unique/continuous numbering to avoid Cloudstream collapsing
                    season    = 1  // override per-season below when needed
                    posterUrl = showPoster
                }
            }
        } catch (t: Throwable) {
            Log.e("StarDima", "fetchSeasonEpisodes($seasonId): ${t.message}")
            emptyList()
        }
    }

    // ─── HTML fallback for episodes ───────────────────────────────────────────

    private fun extractEpisodesFromHtml(doc: Document, poster: String?): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seen     = mutableSetOf<String>()
        var counter  = 1

        val links = doc.select("a[href*=/play/], a[href*=/episode/]").distinct()
        for (a in links) {
            val href = a.attr("abs:href").trim()
            if (href.isBlank() || !seen.add(href)) continue
            if (href.contains("modal") || href.contains("login") || href.contains("signup")) continue

            val rawText = a.text().trim()
            val epName = if (rawText.isNotBlank()
                && !rawText.contains("تشغيل")
                && !rawText.contains("play", ignoreCase = true)
                && rawText.length > 2) rawText else "الحلقة $counter"

            val epPoster = a.selectFirst("img")?.attr("abs:src")?.ifBlank { null }
                ?: a.selectFirst("img")?.attr("data-src")?.ifBlank { null }

            episodes.add(newEpisode(href) {
                name      = epName
                episode   = counter
                posterUrl = epPoster ?: poster
            })
            counter++
        }
        return episodes
    }

    // ─── load ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc    = app.get(url, headers = hdrs()).document
            val title  = pageTitle(doc)
            val plot   = doc.selectFirst("meta[property=og:description], meta[name=description]")
                ?.attr("content")?.trim()
            val poster = doc.selectFirst("meta[property=og:image]")
                ?.attr("content")?.ifBlank { null }

            when {
                isMovie(url) -> {
                    val playLink = doc.select("a[href*=/play/]").firstOrNull()
                        ?.attr("abs:href") ?: url
                    newMovieLoadResponse(title, url, TvType.Movie, playLink) {
                        this.posterUrl = poster
                        this.plot      = plot
                    }
                }
                isTvShow(url) -> {
                    val allEpisodes = mutableListOf<Episode>()

                    // CONFIRMED FIX: Season IDs only exist on the PLAYER page, not the show home page.
                    // Strategy: find the first /play/ link from the show page, fetch that player page,
                    // then extract season IDs from it using confirmed selectors.
                    val firstPlayUrl = doc.select("a[href*=/play/]").firstOrNull()
                        ?.attr("abs:href")?.trim()

                    Log.d("StarDima", "First play URL: $firstPlayUrl")

                    val playerDoc = if (!firstPlayUrl.isNullOrBlank()) {
                        try {
                            app.get(firstPlayUrl, headers = hdrs()).document
                        } catch (e: Throwable) {
                            Log.e("StarDima", "Failed to load player page: ${e.message}")
                            doc
                        }
                    } else doc

                    val seasonIds = extractAllSeasonIds(playerDoc)
                    Log.d("StarDima", "Found season IDs: $seasonIds")

                    if (seasonIds.isNotEmpty()) {
                        for ((idx, sid) in seasonIds.withIndex()) {
                            val eps = fetchSeasonEpisodes(sid, poster)
                            Log.d("StarDima", "Season $sid → ${eps.size} episodes")
                            for (ep in eps) {
                                allEpisodes.add(newEpisode(ep.data) {
                                    name      = ep.name
                                    episode   = ep.episode
                                    season    = idx + 1
                                    posterUrl = ep.posterUrl
                                })
                            }
                        }
                    }

                    // HTML fallback: scrape episode links from the player page
                    if (allEpisodes.isEmpty()) {
                        Log.d("StarDima", "Season API gave no episodes, falling back to HTML")
                        allEpisodes.addAll(extractEpisodesFromHtml(playerDoc, poster))
                    }

                    // Last resort: at least add the one play link we found
                    if (allEpisodes.isEmpty() && !firstPlayUrl.isNullOrBlank()) {
                        Log.w("StarDima", "Fallback: adding single play link as episode 1")
                        allEpisodes.add(newEpisode(firstPlayUrl) {
                            name      = "الحلقة 1"
                            episode   = 1
                            season    = 1
                            posterUrl = poster
                        })
                    }

                    if (allEpisodes.isEmpty()) {
                        Log.w("StarDima", "No episodes at all for $url")
                        return null
                    }

                    newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
                        this.posterUrl = poster
                        this.plot      = plot
                    }
                }
                else -> null
            }
        } catch (t: Throwable) {
            Log.e("StarDima", "load($url): ${t.message}")
            null
        }
    }

    // ─── loadLinks ────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StarDima", "loadLinks: $data")
        var found = false
        var diag = ""
        dbg = ""

        // --- Case 0 (FIX): hyperwatching.com/watch/ID (v2.hyperwatching.com or hyperwatching.com)
        if (data.contains("hyperwatching.com/watch/")) {
            if (resolveWatch(data, "$mainUrl/", subtitleCallback, callback)) return true
            val id = data.substringAfter("/watch/")
                .substringBefore("?").substringBefore("/").trim()
            if (id.isNotBlank()) {
                found = tryHyperwatching("$hwUrl/iframe/$id", subtitleCallback, callback)
                if (found) return true
                found = tryHyperwatching("https://v2.hyperwatching.com/iframe/$id", subtitleCallback, callback)
                if (found) return true
            }
            try {
                val resp = app.get(data, headers = hdrs("$mainUrl/"))
                for (iframe in resp.document.select("iframe[src]")) {
                    val src = iframe.attr("abs:src").trim()
                    if (src.startsWith("http") &&
                        loadExtractor(src, data, subtitleCallback, callback)) return true
                }
                if (tryExtractStreams(resp.text, data, callback)) return true
            } catch (t: Throwable) {
                Log.e("StarDima", "watch page: ${t.message}")
            }
        }

        // --- Case 1: hyperwatching.com iframe URL (most common from episodes)
        if (data.contains("hyperwatching.com/iframe/")) {
            found = tryHyperwatching(data, subtitleCallback, callback)
            if (found) return true
        }

        // --- Case 2: stardima.com play page — extract and route
        if (!found && data.startsWith(mainUrl)) {
            try {
                val resp = app.get(data, headers = hdrs())
                val doc  = resp.document
                val html = resp.text

                // iframes on the play page
                for (iframe in doc.select("iframe[src]")) {
                    val src = iframe.attr("abs:src").trim()
                    if (src.isBlank() || !src.startsWith("http") || src == data) continue
                    if (src.contains("hyperwatching.com/iframe/")) {
                        if (tryHyperwatching(src, subtitleCallback, callback)) { found = true; break }
                    } else {
                        if (loadExtractor(src, data, subtitleCallback, callback)) { found = true; break }
                    }
                }

                // data-url watch buttons
                if (!found) {
                    for (el in doc.select("[data-url]")) {
                        val wu = el.attr("data-url").trim()
                        if (!wu.startsWith("http")) continue
                        if (wu.contains("hyperwatching.com/iframe/")) {
                            if (tryHyperwatching(wu, subtitleCallback, callback)) { found = true; break }
                        } else {
                            if (loadExtractor(wu, data, subtitleCallback, callback)) { found = true; break }
                        }
                    }
                }

                // generic scan: any hyperwatching / known host URL hidden in the HTML or JS
                if (!found) {
                    val clean = html.replace("\\/", "/").replace("&amp;", "&")
                    val urls = Regex("""https?://[^\s"'<>\\]+""").findAll(clean)
                        .map { it.value }
                        .filter { !isImageAsset(it) && (it.contains("hyperwatching.com") || isExtractorHost(it)) }
                        .distinct().toList()
                    for (u in urls) {
                        if (routeCandidateUrl(u, data, subtitleCallback, callback)) { found = true; break }
                    }
                }

                // regex fallback on raw HTML
                if (!found) found = tryExtractStreams(html, data, callback)

                if (!found) {
                    val fetches = Regex("""(?:fetch|get|post|ajax)\(\s*[`'"]([^`'"]{1,90})""")
                        .findAll(html).map { it.groupValues[1] }.distinct().take(6).joinToString(" | ")
                    diag = "play page len=${html.length} iframes=${doc.select("iframe").size} " +
                        "hw=${html.contains("hyperwatching")} fetch=[$fetches]"
                }

            } catch (t: Throwable) {
                Log.e("StarDima", "loadLinks play page: ${t.message}")
            }
        }

        // --- Case 3: direct extractor for any other URL
        if (!found) {
            try {
                if (loadExtractor(data, mainUrl, subtitleCallback, callback)) found = true
            } catch (_: Throwable) {}
        }

        if (!found) {
            Log.w("StarDima", "No stream found: $data $diag")
            if (diag.isNotBlank() || dbg.isNotBlank()) throw ErrorLoadingException("$diag $dbg".trim())
        }
        return found
    }

    private suspend fun routeCandidateUrl(
        raw: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = raw.replace("\\/", "/").replace("&amp;", "&").trim()
        val m = Regex("""hyperwatching\.com/(?:iframe|watch|embed)/([A-Za-z0-9_\-]+)""").find(url)
        if (m != null) {
            val id = m.groupValues[1]
            if (tryHyperwatching("$hwUrl/iframe/$id", subtitleCallback, callback)) return true
            if (tryHyperwatching("https://v2.hyperwatching.com/iframe/$id", subtitleCallback, callback)) return true
            return false
        }
        return try { loadExtractor(url, referer, subtitleCallback, callback) } catch (_: Throwable) { false }
    }

    private fun inertiaJson(doc: Document): org.json.JSONObject? {
        return try {
            val el = doc.selectFirst("[data-page]") ?: return null
            var raw = el.attr("data-page").trim()
            if (!raw.startsWith("{")) raw = el.data().trim().ifBlank { el.html().trim() }
            org.json.JSONObject(raw)
        } catch (t: Throwable) {
            null
        }
    }

    private fun walkJson(node: Any?, path: String, urls: MutableList<String>, servers: MutableMap<String, String>) {
        when (node) {
            is org.json.JSONObject -> {
                val p = path.lowercase()
                if ((p.contains("server") || p.contains("link")) && node.has("id")) {
                    val id = node.opt("id")?.toString().orEmpty()
                    if (id.matches(Regex("\\d{3,10}"))) {
                        servers[id] = node.optString("name", node.optString("title", "Server"))
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    walkJson(node.opt(k), "$path.$k", urls, servers)
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until node.length()) walkJson(node.opt(i), "$path[$i]", urls, servers)
            }
            is String -> if (node.startsWith("http")) urls.add(node.replace("\\/", "/"))
            else -> {}
        }
    }

    /** True only if the URL really serves media (not an HTML/error page). */
    private suspend fun verifyMedia(u: String, referer: String): Boolean {
        return try {
            val r = app.get(
                u,
                headers = mapOf("User-Agent" to desktopUa, "Referer" to referer, "Range" to "bytes=0-1023")
            )
            if (u.contains(".m3u8")) {
                r.text.trimStart().startsWith("#EXTM3U")
            } else {
                val ct = (r.headers["Content-Type"] ?: "").lowercase()
                ct.startsWith("video/") || ct.contains("octet-stream") || ct.contains("mpegurl")
            }
        } catch (t: Throwable) {
            false
        }
    }

    private suspend fun emitMedia(text: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        var ok = false
        val rx = Regex("""https?://[^\s"'\\<>]+\.(?:m3u8|mp4)[^\s"'\\<>]*""")
        for (m in rx.findAll(text).map { it.value }.distinct().take(6)) {
            if (isImageAsset(m)) continue
            if (m.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, m, referer).forEach { callback(it) }
                ok = true
            } else if (verifyMedia(m, referer)) {
                callback(newExtractorLink(name, "$name MP4", m, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to desktopUa, "Referer" to referer)
                })
                ok = true
            }
        }
        return ok
    }

    private suspend fun secureExtract(
        base: String,
        watchUrl: String,
        csrf: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val h = mutableMapOf(
                "User-Agent" to desktopUa,
                "Referer" to watchUrl,
                "Origin" to base,
                "Content-Type" to "application/json",
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest"
            )
            if (csrf.isNotBlank()) { h["X-CSRF-TOKEN"] = csrf; h["x-csrf-token"] = csrf }
            val mt = "application/json".toMediaType()
            val tokenResp = app.post(
                "$base/api/secure/token", headers = h,
                requestBody = org.json.JSONObject().put("url", watchUrl).toString().toRequestBody(mt)
            )
            val token = org.json.JSONObject(tokenResp.text).optString("token", "").trim()
            if (token.isBlank()) return false
            val extResp = app.post(
                "$base/api/secure/extract", headers = h,
                requestBody = org.json.JSONObject().put("token", token).toString().toRequestBody(mt)
            )
            val file = org.json.JSONObject(extResp.text).optJSONObject("data")
                ?.optString("file", "")?.trim().orEmpty()
            if (!file.startsWith("http")) return false
            when {
                file.contains(".m3u8") -> { M3u8Helper.generateM3u8(name, file, watchUrl).forEach { callback(it) }; true }
                file.contains(".mp4") -> emitMedia(file, watchUrl, callback)
                else -> loadExtractor(file, watchUrl, subtitleCallback, callback) || resolveEmbed(file, watchUrl, callback)
            }
        } catch (t: Throwable) {
            Log.d("StarDima", "secureExtract($base): ${t.message}")
            false
        }
    }

    /** Resolves a hyperwatching.com/watch/ID page into real stream links. */
    private suspend fun resolveWatch(
        watchUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val origin = Regex("""^https?://[^/]+""").find(watchUrl)?.value ?: hwUrl
            val resp = app.get(
                watchUrl,
                headers = mapOf(
                    "User-Agent" to desktopUa,
                    "Referer" to referer,
                    "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8"
                )
            )
            var text = resp.text
            if (text.contains("eval(function(p,a,c,k,e")) {
                val unpacked = runCatching { getAndUnpack(text) }.getOrNull()
                if (!unpacked.isNullOrBlank()) text = text + "\n" + unpacked
            }
            text = text.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")

            // 1) direct media inside the page
            if (emitMedia(text, watchUrl, callback)) return true

            // 2) known provider hosts hidden in the page
            val hostUrls = Regex("""https?://[^\s"'\\<>]+""").findAll(text).map { it.value }
                .filter { !isImageAsset(it) && isExtractorHost(it) }.distinct().take(8).toList()
            for (u in hostUrls) {
                if (loadExtractor(u, watchUrl, subtitleCallback, callback)) return true
                if (resolveEmbed(u, watchUrl, callback)) return true
            }

            // 3) iframes
            for (f in resp.document.select("iframe[src]").take(3)) {
                val src = f.attr("abs:src").trim()
                if (!src.startsWith("http") || src == watchUrl) continue
                if (loadExtractor(src, watchUrl, subtitleCallback, callback)) return true
                if (resolveEmbed(src, watchUrl, callback)) return true
            }

            // 3b) Inertia page data (v2 watch page is a single-page app: data lives in data-page JSON)
            val page = inertiaJson(resp.document)
            if (page != null) {
                val props = page.optJSONObject("props") ?: page
                val pUrls = mutableListOf<String>()
                val pServers = linkedMapOf<String, String>()
                walkJson(props, "props", pUrls, pServers)

                for (u in pUrls.distinct().take(20)) {
                    if (isImageAsset(u) || u.contains("hyperwatching.com")) continue
                    if (isExtractorHost(u) || u.contains(".m3u8") || u.contains(".mp4")) {
                        if (emitMedia(u, watchUrl, callback)) return true
                        if (loadExtractor(u, watchUrl, subtitleCallback, callback)) return true
                        if (resolveEmbed(u, watchUrl, callback)) return true
                    }
                }

                val xsrf = runCatching {
                    java.net.URLDecoder.decode(resp.cookies["XSRF-TOKEN"].orEmpty(), "UTF-8")
                }.getOrDefault("")
                val metaCsrf = resp.document.selectFirst("meta[name=csrf-token]")?.attr("content")?.trim().orEmpty()
                val watchId = watchUrl.substringAfter("/watch/").substringBefore("?").substringBefore("/")
                val vids = linkedSetOf(watchId)
                props.optJSONObject("video")?.opt("id")?.toString()?.takeIf { it.isNotBlank() }?.let { vids.add(it) }

                var okApi = false
                for (vid in vids) {
                    for ((sid, nm) in pServers) {
                        if (tryHyperwatchingPost(
                                vid, sid, metaCsrf, watchUrl, nm, subtitleCallback, callback,
                                origin, resp.cookies, xsrf
                            )
                        ) okApi = true
                    }
                    if (okApi) break
                }
                if (okApi) return true

                dbg = (dbg + " | inertia comp=" + page.optString("component") +
                    " servers=" + pServers + " urls=" + pUrls.distinct().take(4).joinToString(",") { it.take(60) } +
                    " props=" + props.toString().take(350)).trim()
            } else {
                dbg = (dbg + " | no data-page; body=" +
                    resp.document.body().html().replace(Regex("\\s+"), " ").take(300)).trim()
            }

            // 4) hyperwatching secure API (same as the website player)
            val csrf = resp.document.selectFirst("meta[name=csrf-token]")?.attr("content")?.trim().orEmpty()
            for (base in listOf(origin, hwUrl).distinct()) {
                if (secureExtract(base, watchUrl, csrf, subtitleCallback, callback)) return true
            }

            val apis = Regex("""["'`](/api/[A-Za-z0-9_\-/]{2,40})""").findAll(text)
                .map { it.groupValues[1] }.distinct().take(8).joinToString(",")
            val ttl = resp.document.title().take(40)
            val scr = resp.document.select("script[src]").take(4).joinToString(",") { it.attr("src").takeLast(40) }
            dbg = (dbg + " | watch page len=${text.length} title=$ttl iframes=${resp.document.select("iframe").size} scripts=[$scr] apis=[$apis]")
            false
        } catch (t: Throwable) {
            Log.e("StarDima", "resolveWatch($watchUrl): ${t.message}")
            dbg = "resolveWatch error: ${t.message}"
            false
        }
    }

    /** Fetches an embed page, unpacks JS if needed, and emits any m3u8/mp4 found. */
    private suspend fun resolveEmbed(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0
    ): Boolean {
        return try {
            val resp = app.get(url, headers = mapOf("User-Agent" to desktopUa, "Referer" to referer))
            var text = resp.text
            if (text.contains("eval(function(p,a,c,k,e")) {
                val unpacked = runCatching { getAndUnpack(text) }.getOrNull()
                if (!unpacked.isNullOrBlank()) text = text + "\n" + unpacked
            }
            text = text.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
            var ok = false
            val rx = Regex("""https?://[^\s"'\\<>]+\.(?:m3u8|mp4)[^\s"'\\<>]*""")
            for (m in rx.findAll(text).map { it.value }.distinct().take(6)) {
                if (m.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, m, url).forEach { callback(it) }
                } else {
                    callback(newExtractorLink(name, "$name MP4", m, ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to desktopUa, "Referer" to url)
                    })
                }
                ok = true
            }
            if (!ok && depth < 1) {
                for (f in resp.document.select("iframe[src]").take(3)) {
                    val src = f.attr("abs:src").trim()
                    if (src.startsWith("http") && src != url) {
                        if (resolveEmbed(src, url, callback, depth + 1)) { ok = true; break }
                    }
                }
            }
            ok
        } catch (t: Throwable) {
            Log.e("StarDima", "resolveEmbed($url): ${t.message}")
            false
        }
    }

    // ─── Hyperwatching API ────────────────────────────────────────────────────

    /**
     * Full hyperwatching.com extraction flow:
     * 1. GET iframe page → extract CSRF token + server link IDs
     * 2. POST /api/videos/{id}/link with each server_link_id
     * 3. Get watch_url from response → pass to loadExtractor
     */
    private suspend fun tryHyperwatching(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Normalize: add user_id=0&subscriptionActive=false if not present
            val normalizedUrl = if (iframeUrl.contains("?")) iframeUrl
                                else "$iframeUrl?user_id=0&subscriptionActive=false"

            val videoId = iframeUrl
                .substringAfter("/iframe/")
                .substringBefore("?")
                .trim()

            if (videoId.isBlank()) return false

            Log.d("StarDima", "Hyperwatching videoId=$videoId")

            // Step 1: fetch iframe page
            val iframeResp = app.get(
                normalizedUrl,
                headers = mapOf(
                    "User-Agent"      to desktopUa,
                    "Referer"         to "$mainUrl/",
                    "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8"
                ),
                allowRedirects = true
            )
            val iframeDoc  = iframeResp.document
            val iframeHtml = iframeResp.text

            var found = false
            val serverNameById = mutableMapOf<String, String>()

            // Fast path: many pages expose the final embed URL directly (lulustream/uqload/etc).
            // Try known extractors immediately before API dance.
            val providerCandidates = mutableSetOf<String>()
            val providerUrlRegex = Regex(
                """https?://[^\s"'<\\]+""",
                RegexOption.IGNORE_CASE
            )
            for (m in providerUrlRegex.findAll(iframeHtml)) {
                val providerUrl = m.value.trim()
                if (isImageAsset(providerUrl)) continue
                if (isSafeFallbackUrl(providerUrl)) providerCandidates.add(providerUrl)
                if (providerUrl.startsWith("http") &&
                    (isExtractorHost(providerUrl) || isLikelyEmbedUrl(providerUrl)) &&
                    loadExtractor(providerUrl, normalizedUrl, subtitleCallback, callback)
                ) found = true
            }
            // Some pages hardcode the default embed directly in startPlayback().
            val launchIframeRegex = Regex("""launchIframe\(\s*["'](https?://[^"']+)["']\s*\)""")
            for (m in launchIframeRegex.findAll(iframeHtml)) {
                val embeddedUrl = m.groupValues[1].trim()
                if (isSafeFallbackUrl(embeddedUrl)) providerCandidates.add(embeddedUrl)
                if (embeddedUrl.startsWith("http") &&
                    (isExtractorHost(embeddedUrl) || isLikelyEmbedUrl(embeddedUrl)) &&
                    loadExtractor(embeddedUrl, normalizedUrl, subtitleCallback, callback)
                ) found = true
            }

            // Extract CSRF token: <meta name="csrf-token" content="...">
            var csrfToken = iframeDoc
                .selectFirst("meta[name='csrf-token'], meta[name=csrf-token]")
                ?.attr("content")?.trim() ?: ""

            // Fallback: look in response headers Set-Cookie or X-CSRF-Token
            if (csrfToken.isBlank()) {
                csrfToken = iframeResp.headers["X-CSRF-TOKEN"] ?: ""
            }

            // Fallback: find in JS
            if (csrfToken.isBlank()) {
                val csrfRegex = Regex("""csrf[_\-]?token['"]\s*[:=,]\s*['"]([A-Za-z0-9+/=_\-]{10,})""", RegexOption.IGNORE_CASE)
                csrfToken = csrfRegex.find(iframeHtml)?.groupValues?.get(1) ?: ""
            }

            Log.d("StarDima", "CSRF token: ${csrfToken.take(15)}...")

            // Extract all server link IDs from the page
            // Pattern 1: data-link-id="535176"
            val serverIds = mutableSetOf<String>()
            for (el in iframeDoc.select("[data-link-id], [data-server-id], [data-id][data-source]")) {
                val sid = (el.attr("data-link-id").ifBlank { null }
                    ?: el.attr("data-server-id").ifBlank { null }
                    ?: el.attr("data-id").ifBlank { null })?.trim()
                if (!sid.isNullOrBlank()) serverIds.add(sid)
            }

            // Pattern 2: server_link_id in JS
            val serverIdRegex = Regex("""server[_\-]?link[_\-]?id['"]\s*[:=]\s*['"]?(\d+)""", RegexOption.IGNORE_CASE)
            for (m in serverIdRegex.findAll(iframeHtml)) {
                serverIds.add(m.groupValues[1])
            }

            // Pattern 2b: JS config.servers IDs, e.g. { id: "319472", name: "Uqload" }
            val configServerIdRegex = Regex("""\bid\s*:\s*["'](\d{3,10})["']""")
            for (m in configServerIdRegex.findAll(iframeHtml)) {
                serverIds.add(m.groupValues[1])
            }
            val configServerRegex = Regex("""\{\s*id\s*:\s*["'](\d{3,10})["']\s*,\s*name\s*:\s*["']([^"']+)["']""")
            for (m in configServerRegex.findAll(iframeHtml)) {
                val sid = m.groupValues[1]
                val sname = m.groupValues[2].trim()
                if (sid.isNotBlank() && sname.isNotBlank()) serverNameById[sid] = sname
            }

            // Pattern 3: look for buttons/divs with link IDs (common pattern: <li data-id="535176">)
            for (el in iframeDoc.select("li[data-id], div[data-id], button[data-id], a[data-id]")) {
                val sid = el.attr("data-id").trim()
                if (sid.matches(Regex("\\d{3,10}"))) serverIds.add(sid)
            }

            Log.d("StarDima", "Found server IDs: $serverIds")
            dbg = (dbg + " | iframe len=${iframeHtml.length} servers=${serverIds.take(4)} csrf=${csrfToken.isNotBlank()}").trim()

            // If no server IDs found, try a generic first request without server_link_id.
            // Do not return early; providerCandidates fallback is handled below.
            if (serverIds.isEmpty()) {
                // Some versions of hyperwatching return the link directly
                found = tryHyperwatchingPost(
                    videoId, null, csrfToken, normalizedUrl, null, subtitleCallback, callback
                ) || found

                // Last-resort: try loadExtractor on the iframe URL directly
                if (!found && loadExtractor(normalizedUrl, mainUrl, subtitleCallback, callback)) found = true
            }
            else {
                // Try each server ID and keep all successful sources (don't stop on first success).
                for (sid in serverIds) {
                    val sidFound = tryHyperwatchingPost(
                        videoId, sid, csrfToken, normalizedUrl, serverNameById[sid], subtitleCallback, callback
                    )
                    if (sidFound) found = true
                }
            }

            // If still nothing, try loadExtractor on the iframe
            if (!found) {
                found = loadExtractor(normalizedUrl, mainUrl, subtitleCallback, callback)
            }

            // Final fallback: resolve embedded provider pages ourselves (never emit HTML pages as video).
            if (!found && providerCandidates.isNotEmpty()) {
                val junk = Regex("""\.(?:js|css|woff2?|ico|json)(?:\?|$)""", RegexOption.IGNORE_CASE)
                val list = providerCandidates.filter { !junk.containsMatchIn(it) }.take(8)
                for (u in list) {
                    if (!isSafeFallbackUrl(u)) continue
                    if (isPlayableCandidate(u) && verifyMedia(u, normalizedUrl)) {
                        val type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        callback(newExtractorLink(name, "$name Server", u, type) {
                            this.referer = normalizedUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to desktopUa, "Referer" to normalizedUrl)
                        })
                        found = true
                    } else if (u.contains("hyperwatching.com/watch/")) {
                        if (resolveWatch(u, normalizedUrl, subtitleCallback, callback)) found = true
                    } else if (resolveEmbed(u, normalizedUrl, callback)) {
                        found = true
                    }
                }
                if (!found) dbg = (dbg + " candidates: " + list.take(5).joinToString(" | ") { it.take(80) }).trim()
            }

            found
        } catch (t: Throwable) {
            Log.e("StarDima", "tryHyperwatching($iframeUrl): ${t.message}")
            false
        }
    }

    /**
     * POST to /api/videos/{videoId}/link with a server_link_id.
     * Returns true if a playable URL was found and dispatched.
     */
    private suspend fun tryHyperwatchingPost(
        videoId: String,
        serverLinkId: String?,
        csrfToken: String,
        referer: String,
        serverName: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        apiBase: String = hwUrl,
        cookies: Map<String, String> = emptyMap(),
        xsrf: String = ""
    ): Boolean {
        return try {
            val body = if (serverLinkId != null)
                "{\"server_link_id\":\"$serverLinkId\"}"
            else "{}"

            val reqHeaders = mutableMapOf(
                "User-Agent"   to desktopUa,
                "Referer"      to referer,
                "Origin"       to apiBase,
                "Content-Type" to "application/json",
                "Accept"       to "*/*"
            )
            if (xsrf.isNotBlank()) reqHeaders["X-XSRF-TOKEN"] = xsrf
            if (csrfToken.isNotBlank()) {
                reqHeaders["X-CSRF-TOKEN"] = csrfToken
                reqHeaders["x-csrf-token"] = csrfToken
            }

            // Send JSON body with proper Content-Type: application/json
            val jsonMediaType = "application/json".toMediaType()
            val resp = app.post(
                "$apiBase/api/videos/$videoId/link",
                headers     = reqHeaders,
                cookies = cookies,
                requestBody = body.toRequestBody(jsonMediaType)
            )

            val json = org.json.JSONObject(resp.text)
            val success = json.optBoolean("success", false) ||
                json.optString("status").equals("completed", ignoreCase = true)
            val watchUrl = json.optString("watch_url", "").trim()

            Log.d("StarDima", "HW POST result success=$success watchUrl=$watchUrl")

            if (!success || watchUrl.isBlank()) {
                if (dbg.length < 500) dbg = (dbg + " | api: " + resp.text.take(100)).trim()
                return false
            }

            Log.d("StarDima", "HW watch_url: $watchUrl")
            val sourceLabel = serverName?.ifBlank { null } ?: "Server"

            val candidates = linkedSetOf<String>()
            fun addCandidate(url: String?) {
                val clean = url?.trim().orEmpty()
                if (isPlayableCandidate(clean) || isExtractorHost(clean)) candidates.add(clean)
            }
            fun addDecodedParams(url: String) {
                val query = url.substringAfter("?", "")
                if (query.isBlank()) return
                for (part in query.split("&")) {
                    val valueEnc = part.substringAfter("=", "")
                    if (valueEnc.isBlank()) continue
                    val decoded = runCatching { java.net.URLDecoder.decode(valueEnc, "UTF-8") }.getOrNull()
                    addCandidate(decoded)
                }
            }

            addCandidate(watchUrl)
            addDecodedParams(watchUrl)

            // Some watch URLs wrap the real host inside query params (e.g. strema.top/?id=<lulustream-url>)
            for (u in candidates.toList()) {
                if (isExtractorHost(u)) {
                    if (loadExtractor(u, watchUrl, subtitleCallback, callback)) return true
                    if (loadExtractor(u, referer, subtitleCallback, callback)) return true
                    if (loadExtractor(u, mainUrl, subtitleCallback, callback)) return true
                }
            }

            // Try Hyperwatching secure extraction flow (same as website player):
            // /api/secure/token -> /api/secure/extract
            try {
                val tokenResp = app.post(
                    "$apiBase/api/secure/token",
                    headers = reqHeaders + mapOf("X-Requested-With" to "XMLHttpRequest"),
                cookies = cookies,
                    requestBody = org.json.JSONObject().put("url", watchUrl)
                        .toString().toRequestBody(jsonMediaType)
                )
                val token = org.json.JSONObject(tokenResp.text).optString("token", "").trim()
                if (token.isNotBlank()) {
                    val extractResp = app.post(
                        "$apiBase/api/secure/extract",
                        headers = reqHeaders + mapOf("X-Requested-With" to "XMLHttpRequest"),
                cookies = cookies,
                        requestBody = org.json.JSONObject().put("token", token)
                            .toString().toRequestBody(jsonMediaType)
                    )
                    val extJson = org.json.JSONObject(extractResp.text)
                    val extData = extJson.optJSONObject("data")
                    val directFile = extData?.optString("file", "")?.trim().orEmpty()
                    if (directFile.startsWith("http")) {
                        if (directFile.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(name, directFile, watchUrl).forEach { callback(it) }
                        } else {
                            callback(ExtractorLink(name, "$name $sourceLabel", directFile, watchUrl, Qualities.Unknown.value, false))
                        }
                        return true
                    }
                }
            } catch (t: Throwable) {
                Log.d("StarDima", "secure extract failed sid=$serverLinkId: ${t.message}")
            }

            // Direct fetch of the watch URL for m3u8/mp4 inside
            val watchResp = try {
                app.get(watchUrl, headers = mapOf(
                    "User-Agent" to desktopUa,
                    "Referer"    to referer
                ))
            } catch (t: Throwable) {
                Log.w("StarDima", "watchUrl fetch failed: ${t.message}")
                null
            }
            val watchHtml = watchResp?.text.orEmpty()
            val watchDoc  = watchResp?.document

            // video/source tags
            for (el in watchDoc?.select("video[src], source[src]").orEmpty()) {
                val src = el.attr("abs:src").trim()
                if (!src.startsWith("http")) continue
                if (src.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, src, watchUrl).forEach { callback(it) }
                    return true
                } else if (src.contains(".mp4")) {
                    callback(ExtractorLink(name, "$name $sourceLabel", src, watchUrl, Qualities.Unknown.value, false))
                    return true
                }
            }

            // regex on HTML
            if (watchHtml.isNotBlank() && tryExtractStreams(watchHtml, watchUrl, callback)) return true

            // Harvest provider URLs from HTML and retry extractor.
            if (watchHtml.isNotBlank()) {
                val providerRegex = Regex(
                    """https?://(?:www\.)?(?:lulustream|uqload|krakenfiles|streamhg|earnvids|goodstream|darkibox|strema\.top)[^\s"'<\\]+""",
                    RegexOption.IGNORE_CASE
                )
                for (m in providerRegex.findAll(watchHtml)) {
                    addCandidate(m.value)
                    addDecodedParams(m.value)
                }
                for (u in candidates.toList()) {
                    if (!isExtractorHost(u)) continue
                    if (loadExtractor(u, watchUrl, subtitleCallback, callback)) return true
                    if (loadExtractor(u, referer, subtitleCallback, callback)) return true
                }
            }

            // Nested iframes
            for (iframe in watchDoc?.select("iframe[src]").orEmpty()) {
                val iSrc = iframe.attr("abs:src").trim()
                if (iSrc.isBlank() || !iSrc.startsWith("http")) continue
                if (loadExtractor(iSrc, watchUrl, subtitleCallback, callback)) return true
                try {
                    val iResp = app.get(iSrc, headers = mapOf("User-Agent" to desktopUa, "Referer" to watchUrl))
                    if (tryExtractStreams(iResp.text, iSrc, callback)) return true
                } catch (_: Throwable) {}
            }

            // FINAL FALLBACK: resolve candidates ourselves (never emit HTML pages as video).
            if (candidates.isNotEmpty()) {
                var ok = false
                for (u in candidates.take(5)) {
                    if (!isSafeFallbackUrl(u)) continue
                    if (isPlayableCandidate(u) && verifyMedia(u, watchUrl)) {
                        val type = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        callback(newExtractorLink(name, "$name $sourceLabel", u, type) {
                            this.referer = watchUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to desktopUa, "Referer" to watchUrl)
                        })
                        ok = true
                    } else if (resolveEmbed(u, watchUrl, callback)) {
                        ok = true
                    }
                }
                if (!ok) dbg = "watch candidates: " + candidates.take(5).joinToString(" | ") { it.take(80) }
                return ok
            }

            // Avoid emitting wrapper/non-playable URLs that can freeze or crash some players.
            false
        } catch (t: Throwable) {
            Log.e("StarDima", "tryHyperwatchingPost sid=$serverLinkId: ${t.message}")
            false
        }
    }

    // ─── Stream regex helper ──────────────────────────────────────────────────

    private fun tryExtractStreams(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // Pattern 1: bare m3u8/mp4 URLs
        val urlRegex = Regex("""https?://[^\s"'\\]+\.(?:mp4|m3u8)(?:\?[^\s"'\\]*)?""")
        for (m in urlRegex.findAll(html)) {
            val src    = m.value
            val isHls  = src.contains(".m3u8")
            callback(ExtractorLink(name, if (isHls) "$name HLS" else "$name MP4",
                src, referer, Qualities.Unknown.value, isHls))
            found = true
        }

        // Pattern 2: JSON key-value
        if (!found) {
            val jsonRegex = Regex(
                """["'](file|url|src|stream|link|source|hls|video)["']\s*:\s*["'](https?://[^"\']+\.(?:mp4|m3u8)[^"\']*)["']""",
                RegexOption.IGNORE_CASE
            )
            for (m in jsonRegex.findAll(html)) {
                val src = m.groupValues[2].trim()
                if (src.isBlank()) continue
                val isHls = src.contains(".m3u8")
                callback(ExtractorLink(name, "$name Stream", src, referer, Qualities.Unknown.value, isHls))
                found = true
            }
        }

        return found
    }
}
