package eu.kanade.tachiyomi.extension.fr.punkrecords

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

@Source
abstract class PunkRecords : KeiSource() {
    override val supportsLatest = true

    private val apiUrl = "https://api.punkrecordz.com/graphql"
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun getPopularManga(page: Int): MangasPage =
        getCatalogPage(page, "updateTime")

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        getCatalogPage(page, "updateTime")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val all = mutableListOf<SManga>()
        var skip = 0
        while (true) {
            val batch = fetchCatalog(skip, CATALOG_LIMIT)
            if (batch.isEmpty()) break
            all += batch
            skip += batch.size
            if (batch.size < CATALOG_LIMIT) break
        }
        val matches = all.filter { it.title.contains(query, ignoreCase = true) }
        val start = ((page - 1) * PAGE_SIZE).coerceAtLeast(0)
        return MangasPage(matches.drop(start).take(PAGE_SIZE), start + PAGE_SIZE < matches.size)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.url.substringAfter("/mangas/").substringBefore("/")
        val updatedManga = if (fetchDetails) fetchManga(slug) ?: manga else manga
        val updatedChapters = if (fetchChapters) fetchChapters(slug) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val slug = chapter.url.substringAfter("/mangas/").substringBefore("/")
        val number = chapter.url.substringAfterLast("/")
        val query = """
            query ChapterPages(${'$'}slug: String, ${'$'}number: Float) {
              chapter(where: {
                deleted: false
                published: true
                number: ${'$'}number
                manga: { slug: ${'$'}slug }
              }) {
                pages { original colored }
              }
            }
        """.trimIndent()
        val data = graphQl(query, JSONObject().put("slug", slug).put("number", number.toDoubleOrNull() ?: 0.0))
        val pages = data.optJSONObject("chapter")?.optJSONArray("pages") ?: JSONArray()
        return (0 until pages.length()).mapNotNull { index ->
            val item = pages.optJSONObject(index) ?: return@mapNotNull null
            val path = item.optString("colored").takeIf { it.isNotBlank() && it != "null" }
                ?: item.optString("original").takeIf { it.isNotBlank() && it != "null" }
                ?: return@mapNotNull null
            val imageUrl = imageUrl(path)
            Page(index, imageUrl, null)
        }
    }

    private suspend fun getCatalogPage(page: Int, orderField: String): MangasPage {
        val items = fetchCatalog((page - 1) * PAGE_SIZE, PAGE_SIZE, orderField)
        return MangasPage(items, items.size == PAGE_SIZE)
    }

    private suspend fun fetchCatalog(skip: Int, limit: Int, orderField: String = "updateTime"): List<SManga> {
        val query = """
            query Catalog(${'$'}skip: Int!, ${'$'}limit: Int!, ${'$'}order: Float!) {
              mangas(
                skip: ${'$'}skip
                limit: ${'$'}limit
                where: { published: true, deleted: false }
                order: [{ field: "$orderField", order: ${'$'}order }]
              ) {
                slug name thumb keywords story status statusProgression
              }
            }
        """.trimIndent()
        val data = graphQl(query, JSONObject().put("skip", skip).put("limit", limit).put("order", -1))
        val array = data.optJSONArray("mangas") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.toManga()
        }
    }

    private suspend fun fetchManga(slug: String): SManga? {
        val query = """
            query MangaBySlug(${'$'}slug: String!) {
              manga(where: { slug: ${'$'}slug, published: true, deleted: false }) {
                slug name thumb keywords story status statusProgression
              }
            }
        """.trimIndent()
        return graphQl(query, JSONObject().put("slug", slug))
            .optJSONObject("manga")?.toManga()
    }

    private suspend fun fetchChapters(slug: String): List<SChapter> {
        val query = """
            query Chapters(${'$'}slug: String!, ${'$'}limit: Int!, ${'$'}skip: Int!, ${'$'}order: Float!) {
              chapters(
                limit: ${'$'}limit
                skip: ${'$'}skip
                where: {
                  deleted: false
                  published: true
                  manga: { slug: ${'$'}slug, published: true, deleted: false }
                }
                order: [{ field: "number", order: ${'$'}order }]
              ) { number }
            }
        """.trimIndent()
        val result = mutableListOf<SChapter>()
        var skip = 0
        while (true) {
            val data = graphQl(
                query,
                JSONObject().put("slug", slug).put("limit", CHAPTER_LIMIT).put("skip", skip).put("order", -1),
            )
            val array = data.optJSONArray("chapters") ?: break
            if (array.length() == 0) break
            for (index in 0 until array.length()) {
                val rawNumber = array.optJSONObject(index)?.opt("number")?.toString() ?: continue
                val number = rawNumber.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: rawNumber
                val url = "/mangas/$slug/$number"
                result += SChapter.create().apply {
                    name = "Chapitre $number"
                    this.url = url
                    chapter_number = number.toFloatOrNull() ?: -1f
                }
            }
            skip += array.length()
            if (array.length() < CHAPTER_LIMIT) break
        }
        return result
    }

    private fun JSONObject.toManga(): SManga {
        val slug = optString("slug")
        return SManga.create().apply {
            title = optString("name", slug)
            url = "/mangas/$slug"
            thumbnail_url = imageUrl(optString("thumb"))
            description = optString("story").ifBlank { optString("status") }
            genre = optString("keywords").replace(",", ", ")
            status = when (optInt("statusProgression", -1)) {
                4 -> SManga.COMPLETED
                1, 2, 3 -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    private fun imageUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        if (path.isBlank() || path == "null") return ""
        val clean = path.removePrefix("/")
        return when {
            clean.startsWith("images/") -> "https://api.punkrecordz.com/$clean"
            clean.startsWith("webp/") -> "https://api.punkrecordz.com/images/$clean"
            else -> "https://api.punkrecordz.com/images/webp/$clean.webp"
        }
    }

    private suspend fun graphQl(query: String, variables: JSONObject): JSONObject {
        val body = JSONObject().put("query", query).put("variables", variables).toString()
            .toRequestBody(jsonType)
        val request = Request.Builder()
            .url(apiUrl)
            .post(body)
            .header("Origin", "https://punkrecordz.com")
            .header("Referer", "https://punkrecordz.com/")
            .header("Accept", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Punk Records HTTP ${response.code}")
            val result = JSONObject(response.body?.string().orEmpty())
            val errors = result.optJSONArray("errors")
            if (errors != null && errors.length() > 0 && !result.has("data")) {
                throw Exception(errors.optJSONObject(0)?.optString("message") ?: "GraphQL error")
            }
            return result.optJSONObject("data") ?: JSONObject()
        }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"
    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    private companion object {
        const val PAGE_SIZE = 20
        const val CATALOG_LIMIT = 200
        const val CHAPTER_LIMIT = 100
    }
}
