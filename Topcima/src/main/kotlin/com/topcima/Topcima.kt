@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "NewApi")

package com.topcima

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.requestCreator
import okhttp3.Interceptor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class Topcima : MainAPI() {
    override var mainUrl = "https://web.topcinema.io"
    override var name = "Topcima"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val usesWebView = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val cfInterceptor: Interceptor get() = cloudflareKiller

    private val mobileUa =
        "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun standardHeaders(referer: String = "$mainUrl/") = mapOf(
        "User-Agent" to mobileUa,
        "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    private fun postHeaders(referer: String = "$mainUrl/") = standardHeaders(referer) + mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Accept" to "*/*"
    )

    // ─── Safe URL helpers (mirrors StarDima pattern) ───────────────────────

    private fun isImageAsset(url: String) =
        url.contains(".svg", true) || url.contains(".png", true) ||
            url.contains(".jpg", true) || url.contains(".jpeg", true) ||
            url.contains(".webp", true) || url.contains(".gif", true)

    private fun isPlayableCandidate(url: String): Boolean {
        if (!url.startsWith("http")) return false
        if (isImageAsset(url)) return false
        return url.contains(".m3u8", true) || url.contains(".mp4", true)
    }

    private fun isExtractorHost(url: String): Boolean {
        if (!url.startsWith("http")) return false
        return Regex(
            """https?://(?:www\.)?(?:lulustream|uqload|krakenfiles|streamhg|earnvids|goodstream|darkibox|streamwish|vidhide|filelions|doodstream|streamtape|mixdrop|upstream|voe|strema\.top|vidtube|akwam|akamaized|cloudfront|yodbox|shahid4u)[^/\s]*""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(url)
    }

    private fun isLikelyEmbedUrl(url: String): Boolean {
        if (!url.startsWith("http")) return false
        if (isImageAsset(url)) return false
        val lowered = url.lowercase()
        return lowered.contains("/embed/") ||
            lowered.contains("/e/") ||
            lowered.contains("player") ||
            lowered.contains("/watch/") ||
            lowered.contains("/v/") ||
            lowered.contains("play.php")
    }

    private fun isSafeFallbackUrl(url: String): Boolean =
        isPlayableCandidate(url) || isExtractorHost(url) || isLikelyEmbedUrl(url)

    private fun getBaseUrl(url: String): String {
        return try {
            val uri = URI(url)
            "${uri.scheme}://${uri.authority}"
        } catch (_: Exception) {
            mainUrl
        }
    }

    // ─── HTTP helpers ──────────────────────────────────────────────────────

    private suspend fun httpGet(url: String, referer: String? = null) =
        app.get(
            url,
            referer = referer ?: mainUrl,
            headers = standardHeaders(referer ?: mainUrl),
            interceptor = cfInterceptor
        )

    private suspend fun httpPost(
        url: String,
        data: Map<String, String>,
        referer: String? = null
    ) = app.post(
        url,
        data = data,
        referer = referer ?: mainUrl,
        headers = postHeaders(referer ?: mainUrl),
        interceptor = cfInterceptor
    )

    // ─── Main page & search ────────────────────────────────────────────────

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = try {
            httpGet(mainUrl).document
        } catch (t: Throwable) {
            Log.e("Topcima", "getMainPage: ${t.message}")
            return newHomePageResponse(request.name, emptyList())
        }
        val homePageList = arrayListOf<HomePageList>()

        val mainSlider = document.select(".Slides--Main .Slides--Item")
        if (mainSlider.isNotEmpty()) {
            val featuredList = mainSlider.mapNotNull { toSearchResponse(it) }
            if (featuredList.isNotEmpty())
                homePageList.add(HomePageList("أبرز العروض", featuredList))
        }

        document.select("section.Two--Items").forEach { section ->
            try {
                val title = section.selectFirst(".Title--Box h3")?.text()?.trim() ?: return@forEach
                val items = section.select(".Posts--List .Small--Box").mapNotNull {
                    toSearchResponse(it)
                }
                if (items.isNotEmpty()) {
                    homePageList.add(HomePageList(title, items))
                }
            } catch (e: Exception) {
                logError(e)
            }
        }
        return newHomePageResponse(homePageList)
    }

    private fun toSearchResponse(element: Element): SearchResponse? {
        val link = element.selectFirst("a") ?: return null
        val href = link.attr("abs:href").ifBlank { link.attr("href") }
        if (href.isBlank()) return null
        val title = link.attr("title").ifBlank {
            link.selectFirst("h2, h3, .title, .name")?.text()?.trim() ?: return null
        }
        val posterUrl: String? = link.selectFirst("img")?.let {
            it.attr("abs:data-src").ifBlank { it.attr("data-src") }
                .ifBlank { it.attr("abs:src") }.ifBlank { it.attr("src") }
        }?.trim()?.takeIf { it.isNotBlank() }

        val isMovie = title.contains("فيلم") || href.contains("/movie/", true) || href.contains("/film/", true)
        val isSeries = title.contains("مسلسل") || href.contains("/series/", true) || element.selectFirst(".number, .epnum") != null
        val isAnime = title.contains("انمي") || title.contains("أنمي") || title.contains("كرتون") ||
            href.contains("/anime/", true) || href.contains("/cartoon/", true)

        val type = when {
            isAnime -> TvType.Anime
            isSeries -> TvType.TvSeries
            else -> TvType.Movie
        }

        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
            TvType.Anime -> newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
            else -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().ifBlank { return emptyList() }
        return try {
            val url = "$mainUrl/search/?query=${java.net.URLEncoder.encode(q, "UTF-8")}&type=all"
            val document = httpGet(url).document
            document.select(".Posts--List .Small--Box").mapNotNull { toSearchResponse(it) }
        } catch (t: Throwable) {
            Log.e("Topcima", "search: ${t.message}")
            emptyList()
        }
    }

    // ─── Load (movie / series) ─────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val document = httpGet(url).document

            val title = document.selectFirst("h1.post-title a")?.text()?.trim()
                ?: document.selectFirst("h1.post-title")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: return null

            val poster: String? = document.selectFirst(".MainSingle .left .image img")?.let {
                it.attr("abs:data-src").ifBlank { it.attr("abs:src") }
            }?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }

            val plot = document.selectFirst(".story p")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

            val tags = document.select(".RightTaxContent li:contains(نوع) a").map { it.text() }
            val year =
                document.selectFirst(".RightTaxContent li:contains(الصدور) a")?.text()?.toIntOrNull()
            val scoreValue = document.selectFirst(".imdbR span")?.text()?.toDoubleOrNull()
            val actors =
                document.select(".RightTaxContent li.actor a").map { Actor(it.text(), it.attr("abs:href")) }

            val isTvSeries = document.selectFirst("section.tabs, section.allseasonss") != null

            if (isTvSeries) {
                var episodes = emptyList<Episode>()
                val seasonsElements = document.select("section.allseasonss .Small--Box.Season a")
                if (seasonsElements.isNotEmpty()) {
                    episodes = seasonsElements.amap { seasonLink ->
                        val seasonUrl = seasonLink.attr("abs:href").ifBlank { seasonLink.attr("href") }
                        val seasonPoster: String? = seasonLink.selectFirst("img")?.let {
                            it.attr("abs:data-src").ifBlank { it.attr("abs:src") }.takeIf { it.isNotBlank() }
                        }
                        val seasonNum =
                            seasonLink.selectFirst(".epnum")?.text()?.replace("الموسم", "")?.trim()
                                ?.toIntOrNull()

                        val seasonDoc = try {
                            httpGet(seasonUrl).document
                        } catch (_: Exception) {
                            return@amap emptyList()
                        }
                        seasonDoc.select(".allepcont .row > a").map { ep ->
                            val epUrl = ep.attr("abs:href").ifBlank { ep.attr("href") }
                            val data = "$epUrl/watch/||$epUrl/download/"
                            val epTitle = ep.selectFirst("h2")?.text()?.trim()
                            val epThumb: String? = ep.selectFirst("img")?.let {
                                it.attr("abs:data-src").ifBlank { it.attr("abs:src") }.takeIf { it.isNotBlank() }
                            }
                            val episodeNumber =
                                ep.selectFirst(".epnum")?.text()?.replace("الحلقة", "")?.trim()
                                    ?.toIntOrNull()
                            newEpisode(data = data) {
                                name = epTitle
                                season = seasonNum
                                episode = episodeNumber
                                posterUrl = epThumb ?: seasonPoster
                            }
                        }.reversed()
                    }.flatten()
                }
                if (episodes.isEmpty()) {
                    val seasonNumFromTitle = title.let {
                        Regex("""الموسم (\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull()
                    } ?: 1
                    episodes = document.select(".allepcont .row > a").map { ep ->
                        val epUrl = ep.attr("abs:href").ifBlank { ep.attr("href") }
                        val data = "$epUrl/watch/||$epUrl/download/"
                        val epTitle = ep.selectFirst("h2")?.text()?.trim()
                        val epThumb: String? = ep.selectFirst("img")?.let {
                            it.attr("abs:data-src").ifBlank { it.attr("abs:src") }.takeIf { it.isNotBlank() }
                        }
                        val episodeNumber =
                            ep.selectFirst(".epnum")?.text()?.replace("الحلقة", "")?.trim()
                                ?.toIntOrNull()

                        newEpisode(data = data) {
                            name = epTitle
                            season = seasonNumFromTitle
                            episode = episodeNumber
                            posterUrl = epThumb ?: poster
                        }
                    }.reversed()
                }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                    this.score = Score.from10(scoreValue)
                }
            } else {
                val data = "$url/watch/||$url/download/"
                newMovieLoadResponse(title, url, TvType.Movie, data) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                    this.score = Score.from10(scoreValue)
                }
            }
        } catch (t: Throwable) {
            Log.e("Topcima", "load($url): ${t.message}")
            logError(t)
            null
        }
    }

    // ─── Link resolution helpers ───────────────────────────────────────────

    private fun unpackJs(p: String, a: Int, c: Int, k: List<String>): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        fun intToBase(numInput: Int, base: Int): String {
            if (numInput == 0) return "0"
            var n = numInput
            val sb = StringBuilder()
            while (n > 0) {
                sb.append(digits[n % base])
                n /= base
            }
            return sb.reverse().toString()
        }

        val mapping = mutableMapOf<String, String>()
        for (i in 0 until c) {
            val key = intToBase(i, a)
            val value = k.getOrNull(i)
            if (!value.isNullOrBlank()) mapping[key] = value
        }

        return Regex("([0-9A-Za-z]+)").replace(p) { m -> mapping[m.value] ?: m.value }
    }

    private fun unwrapPlayUrl(url: String): String {
        return try {
            if (url.contains("play.php?to=")) {
                val decoded = java.net.URLDecoder.decode(url.substringAfter("play.php?to="), "UTF-8").trim()
                if (decoded.startsWith("http")) decoded else "https:${decoded.trimStart(':')}"
            } else url
        } catch (_: Exception) { url }
    }

    private suspend fun extractVidtube(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            Log.d("Topcima", "[Vidtube] Loading: $url (referer=$referer)")
            val base = getBaseUrl(url)
            val response = app.get(
                url,
                headers = standardHeaders(referer),
                referer = referer,
                interceptor = cfInterceptor
            ).text

            var pRaw: String? = null
            var a = 0
            var c = 0
            var kList: List<String> = emptyList()

            val packerRegex = Regex(
                """eval\(function\(p,a,c,k,e,d\)\{.*?\}\(\s*(['"])(.*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])(.*?)\5\.split""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            )
            val match = packerRegex.find(response)
            if (match != null) {
                try {
                    pRaw = match.groupValues[2]
                    a = match.groupValues[3].toInt()
                    c = match.groupValues[4].toInt()
                    val kStr = match.groupValues[6]
                    kList = if (kStr.isEmpty()) emptyList() else kStr.split("|")
                    Log.d("Topcima", "[Vidtube] Regex parse OK -> a=$a c=$c k=${kList.size}")
                } catch (e: Exception) {
                    Log.w("Topcima", "[Vidtube] Regex parse partial failure: ${e.message}")
                }
            }
            if (pRaw == null) {
                try {
                    val evalStart = response.indexOf("eval(function(p,a,c,k,e,d)")
                    if (evalStart >= 0) {
                        val sub = response.substring(evalStart)
                        val lastSplitIdx = sub.lastIndexOf(".split('|')")
                        if (lastSplitIdx > 0) {
                            val beforeSplit = sub.substring(0, lastSplitIdx)
                            val quoteIdx = beforeSplit.lastIndexOf("'")
                            val quoteIdx2 = beforeSplit.lastIndexOf("\"")
                            val idx = maxOf(quoteIdx, quoteIdx2)
                            if (idx >= 0) {
                                val kStr = beforeSplit.substring(idx + 1).trim()
                                kList = if (kStr.isBlank()) emptyList() else kStr.split("|")
                            }

                            val tail = sub.substring(0, lastSplitIdx)
                            val nums = Regex("""\(\s*(['"']).*?['"']\s*,\s*(\d+)\s*,\s*(\d+)\s*,""", RegexOption.DOT_MATCHES_ALL).find(tail)
                            if (nums != null) {
                                a = nums.groupValues[2].toIntOrNull() ?: a
                                c = nums.groupValues[3].toIntOrNull() ?: c
                            }

                            val pCandidateMatch = Regex("""\(\s*(['"])(.*?)\1\s*,\s*$a\s*,\s*$c""", RegexOption.DOT_MATCHES_ALL).find(sub)
                            if (pCandidateMatch != null) {
                                pRaw = pCandidateMatch.groupValues[2]
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("Topcima", "[Vidtube] fallback parse failed: ${e.message}")
                }
            }

            // Fast path: look for m3u8 URLs directly in the page even before unpacking
            found = extractDirectStreams(response, url, base, callback) || found

            if (pRaw != null) {
                val p = pRaw.replace("\\'", "'")
                val safeA = if (a <= 0) 62 else minOf(a, 62)
                val safeC = if (c <= 0) kList.size.coerceAtLeast(0) else c

                val unpacked = try {
                    unpackJs(p, safeA, safeC, kList)
                } catch (e: Exception) {
                    Log.e("Topcima", "[Vidtube] unpackJs failed: ${e.message}")
                    ""
                }

                found = extractDirectStreams(unpacked, url, base, callback) || found

                val fileRegex = Regex("""file\s*:\s*"(https?://[^"]+)"""")
                val labelRegex = Regex("""label\s*:\s*"([^"]+)"""")
                val files = fileRegex.findAll(unpacked).map { it.groupValues[1] }.toList()
                val labels = labelRegex.findAll(unpacked).map { it.groupValues[1] }.toList()

                files.forEachIndexed { index, fileUrl ->
                    val label = labels.getOrNull(index) ?: "Auto"
                    try {
                        if (fileUrl.contains(".m3u8", true)) {
                            M3u8Helper.generateM3u8(
                                source = name,
                                streamUrl = fileUrl,
                                referer = url,
                                name = "Vidtube - $label"
                            ).forEach { callback(it) }
                        } else {
                            callback(newExtractorLink(
                                source = name,
                                name = "Vidtube - $label",
                                url = fileUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = url
                                this.quality = getQualityFromName(label)
                                this.headers = standardHeaders(url)
                            })
                        }
                        found = true
                        Log.d("Topcima", "[Vidtube] Found Link: $label -> $fileUrl")
                    } catch (e: Exception) {
                        Log.e("Topcima", "[Vidtube] callback failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Topcima", "[Vidtube] Connection/overall error: ${e.message}")
            logError(e)
        }
        return found
    }

    private suspend fun extractDirectStreams(
        html: String,
        pageUrl: String,
        baseUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val m3u8Regex = Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""", RegexOption.IGNORE_CASE)
            for (m in m3u8Regex.findAll(html)) {
                val url = m.value.trim()
                if (!url.startsWith("http")) continue
                try {
                    M3u8Helper.generateM3u8(name, url, baseUrl).forEach {
                        callback(it)
                        found = true
                    }
                } catch (_: Exception) {}
            }
            val mp4Regex = Regex("""https?://[^\s"'\\]+\.mp4[^\s"'\\]*""", RegexOption.IGNORE_CASE)
            for (m in mp4Regex.findAll(html)) {
                val url = m.value.trim()
                if (!url.startsWith("http") || isImageAsset(url)) continue
                try {
                    callback(newExtractorLink(
                        source = name,
                        name = "$name MP4",
                        url = url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = pageUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = standardHeaders(pageUrl)
                    })
                    found = true
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return found
    }

    // WebView fallback for hosts that need a JS environment (fixes the "works only after browser warmup" issue)
    private suspend fun resolveWithWebView(
        iframeUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            Log.d("Topcima", "[WebView] Trying fallback for $iframeUrl (ref=$referer)")

            try {
                app.get(
                    iframeUrl,
                    referer = referer,
                    interceptor = cfInterceptor,
                    headers = standardHeaders(referer)
                )
            } catch (_: Exception) {}

            val resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8|\.mp4"""),
                script = """
                    (function() {
                        try {
                            ['.play-button','.btn-play','.vjs-big-play-button','.jw-icon-display','button[class*=play]','.plyr__play-large']
                                .forEach(function(s){var e=document.querySelector(s);if(e){try{e.click();}catch(_){}}});
                            var vids = document.querySelectorAll('video source, video');
                            vids.forEach(function(v){ try{ var s = v.src || v.getAttribute('src'); if(s){} }catch(_){} });
                            var ifs = document.querySelectorAll('iframe');
                            ifs.forEach(function(f){ try{ var s = f.src; if(s && s.indexOf('http')===0){ window.location.href = s; } }catch(_){} });
                        } catch(_) {}
                    })();
                """.trimIndent()
            )

            val videoUrl = resolver.resolveUsingWebView(
                requestCreator(
                    "GET",
                    iframeUrl,
                    referer = referer,
                    headers = standardHeaders(referer)
                )
            ).first?.url?.toString()

            if (!videoUrl.isNullOrBlank()) {
                Log.d("Topcima", "[WebView] intercepted: $videoUrl")
                if (videoUrl.contains(".m3u8", true)) {
                    M3u8Helper.generateM3u8(name, videoUrl, referer).forEach {
                        callback(it)
                    }
                    true
                } else if (videoUrl.contains(".mp4", true)) {
                    callback(newExtractorLink(
                        source = name,
                        name = "$name WebView",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                        this.headers = standardHeaders(referer)
                    })
                    true
                } else false
            } else false
        } catch (t: Throwable) {
            Log.e("Topcima", "[WebView] resolve failed: ${t.message}")
            false
        }
    }

    // loadLinks — WebView-first approach (Cloudflare-safe)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Topcima", "loadLinks data=$data")

        // data format: "episodeUrl/watch/||episodeUrl/download/"
        // Primary: the /watch/ URL
        val watchUrl = data.split("||").firstOrNull { it.contains("/watch/") }
            ?: data.split("||").firstOrNull { it.startsWith("http") }
            ?: return false

        val epBase = watchUrl.substringBeforeLast("/watch/")
        val referer = "$epBase/"
        var foundAny = false

        // ── Step 1: WebViewResolver intercepts the video stream directly ──────
        try {
            Log.d("Topcima", "[WV] Trying WebView for $watchUrl")
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8|\.mp4|/playlist|/index\.m3u8""", RegexOption.IGNORE_CASE),
                additionalUrls = listOf(Regex("""https?://.*\.(m3u8|mp4).*""", RegexOption.IGNORE_CASE))
            )
            val (videoRequest, _) = resolver.resolveUsingWebView(
                requestCreator("GET", watchUrl, referer = referer, headers = standardHeaders(referer))
            )
            val videoUrl = videoRequest?.url?.toString()
            if (!videoUrl.isNullOrBlank()) {
                Log.d("Topcima", "[WV] intercepted: $videoUrl")
                if (videoUrl.contains(".m3u8", true)) {
                    M3u8Helper.generateM3u8(name, videoUrl, referer).forEach {
                        callback(it); foundAny = true
                    }
                } else {
                    callback(newExtractorLink(source = name, name = "$name WebView", url = videoUrl,
                        type = ExtractorLinkType.VIDEO) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                        this.headers = standardHeaders(referer)
                    })
                    foundAny = true
                }
            }
        } catch (e: Exception) {
            Log.w("Topcima", "[WV] failed: ${e.message}")
        }

        // ── Step 2: HTTP fallback — try CloudflareKiller + scrape iframes ─────
        if (!foundAny) {
            try {
                Log.d("Topcima", "[HTTP] Trying HTTP scrape for $watchUrl")
                val response = app.get(
                    watchUrl,
                    referer = referer,
                    headers = standardHeaders(referer),
                    interceptor = cfInterceptor
                )
                val finalUrl = response.url
                val baseUrl = getBaseUrl(finalUrl)
                val doc = response.document
                val html = response.text

                // Collect all embed candidates
                val embeds = linkedSetOf<String>()

                fun addEmbed(src: String) {
                    if (src.isBlank() || src.startsWith("javascript") || src.startsWith("#")) return
                    val abs = if (src.startsWith("http")) src else "$baseUrl$src"
                    if (abs.startsWith("http") && !isImageAsset(abs)) embeds.add(abs)
                }

                // Iframes
                doc.select("iframe[src]").forEach { addEmbed(it.attr("abs:src").ifBlank { it.attr("src") }) }

                // data-* attributes
                doc.select("[data-link],[data-embed],[data-src],[data-url]").forEach { el ->
                    listOf("data-link","data-embed","data-src","data-url").forEach { attr ->
                        val v = el.attr(attr).trim()
                        if (v.isNotBlank()) addEmbed(v)
                    }
                }

                // AJAX server buttons
                val serverItems = doc.select("li.server--item, li[data-id], [data-server]")
                if (serverItems.isNotEmpty()) {
                    val ajaxUrl = "$baseUrl/wp-content/themes/movies2023/Ajaxat/Single/Server.php"
                    serverItems.amap { server ->
                        val id  = server.attr("data-id").trim()
                        val idx = server.attr("data-server").ifBlank { server.attr("data-i") }.trim()
                        if (id.isBlank() && idx.isBlank()) return@amap
                        try {
                            val res = app.post(ajaxUrl,
                                data = mapOf("id" to id, "i" to idx),
                                referer = finalUrl,
                                headers = postHeaders(finalUrl),
                                interceptor = cfInterceptor
                            ).text
                            Jsoup.parse(res).select("iframe[src]").forEach {
                                addEmbed(it.attr("abs:src").ifBlank { it.attr("src") })
                            }
                            Regex("""https?://[^\s"'<>\\]+""").findAll(res).forEach { m ->
                                val u = m.value.trimEnd('\\', ')', '"', '\'')
                                if (isSafeFallbackUrl(u)) addEmbed(u)
                            }
                        } catch (_: Exception) {}
                    }
                }

                // Raw HTML scan for extractor hosts
                Regex("""https?://[^\s"'<>\\]+""").findAll(html).forEach { m ->
                    val u = m.value.trimEnd('\\', ')', '"', '\'')
                    if (isExtractorHost(u) || isPlayableCandidate(u)) addEmbed(u)
                }

                Log.d("Topcima", "[HTTP] Found ${embeds.size} embed candidates")

                // Try each embed
                val tried = mutableSetOf<String>()
                embeds.toList().amap { rawEmbed ->
                    val link = unwrapPlayUrl(rawEmbed)
                    if (!tried.add(link)) return@amap
                    try {
                        var ok = when {
                            link.contains("vidtube", ignoreCase = true) ->
                                extractVidtube(link, finalUrl, callback)
                            else -> loadExtractor(link, finalUrl, subtitleCallback, callback)
                        }
                        if (!ok) {
                            try {
                                val iHtml = app.get(link, referer = finalUrl,
                                    headers = standardHeaders(finalUrl),
                                    interceptor = cfInterceptor).text
                                ok = extractDirectStreams(iHtml, link, baseUrl, callback)
                            } catch (_: Exception) {}
                        }
                        if (ok) foundAny = true
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w("Topcima", "[HTTP] scrape failed: ${e.message}")
            }
        }

        // ── Step 3: WebView iframe interceptor fallback ───────────────────────
        if (!foundAny) {
            foundAny = resolveWithWebView(watchUrl, referer, callback)
        }

        Log.d("Topcima", "loadLinks done foundAny=$foundAny")
        return foundAny
    }
}
