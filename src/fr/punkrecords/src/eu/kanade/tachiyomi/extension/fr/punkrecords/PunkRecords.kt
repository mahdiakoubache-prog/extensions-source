package eu.kanade.tachiyomi.extension.fr.punkrecords

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Source
abstract class PunkRecords : KeiSource() {
    override val supportsLatest = true

    override suspend fun getPopularManga(page: Int): MangasPage = getCatalogPage(page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = getCatalogPage(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val matches = fetchCatalog().filter { it.title.contains(query, ignoreCase = true) }
        val start = ((page - 1) * PAGE_SIZE).coerceAtLeast(0)
        return MangasPage(matches.drop(start).take(PAGE_SIZE), start + PAGE_SIZE < matches.size)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.url.substringAfterLast("/")
        val updatedManga = if (fetchDetails) fetchManga(slug) ?: manga else manga
        val updatedChapters = if (fetchChapters) fetchChapters(slug) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    // The reader is JavaScript-driven. This first version focuses on catalogue and chapter metadata.
    override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()

    private suspend fun getCatalogPage(page: Int): MangasPage {
        val all = fetchCatalog()
        val start = ((page - 1) * PAGE_SIZE).coerceAtLeast(0)
        return MangasPage(all.drop(start).take(PAGE_SIZE), start + PAGE_SIZE < all.size)
    }

    private suspend fun fetchCatalog(): List<SManga> {
        val doc = fetchDocument("/catalogue")
        val seen = mutableSetOf<String>()
        return doc.select("a[href^=/manga/]").mapNotNull { link ->
            val path = link.attr("href").substringBefore("?").trimEnd('/')
            val slug = path.substringAfterLast("/")
            if (slug.isBlank() || !seen.add(slug)) return@mapNotNull null

            val title = link.select("h2, h3, h4").firstOrNull()?.text()
                ?.takeIf { it.isNotBlank() }
                ?: link.text().substringBefore("CH.").trim()
            if (title.isBlank()) return@mapNotNull null

            SManga.create().apply {
                this.title = title
                url = "/manga/$slug"
                thumbnail_url = ""
            }
        }
    }

    private suspend fun fetchManga(slug: String): SManga? {
        val doc = fetchDocument("/manga/$slug")
        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isBlank()) return null

        val description = doc.selectFirst("meta[name=description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: doc.select("main p").firstOrNull()?.text().orEmpty()

        return SManga.create().apply {
            this.title = title
            url = "/manga/$slug"
            this.description = description
            thumbnail_url = ""
            status = SManga.UNKNOWN
        }
    }

    private suspend fun fetchChapters(slug: String): List<SChapter> {
        val doc = fetchDocument("/manga/$slug")
        val chapterPattern = Regex("""^/${'$'}slug/([0-9]+(?:\\.[0-9]+)?)$""")
        val chapters = linkedMapOf<String, SChapter>()

        doc.select("a[href]").forEach { link ->
            val path = link.attr("href").substringBefore("?").trimEnd('/')
            val match = chapterPattern.matchEntire(path) ?: return@forEach
            val number = match.groupValues[1]
            chapters[number] = SChapter.create().apply {
                name = "Chapitre $number"
                url = "/$slug/$number"
                chapter_number = number.toFloatOrNull() ?: -1f
            }
        }

        return chapters.values.sortedByDescending { it.chapter_number }
    }

    private suspend fun fetchDocument(path: String): Document {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .get()
            .header("Referer", "$baseUrl/")
            .header("Accept", "text/html")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Punk Records HTTP ${'$'}{response.code}")
            return Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${'$'}{manga.url}"
    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${'$'}{chapter.url}"

    private companion object {
        const val PAGE_SIZE = 20
    }
}
