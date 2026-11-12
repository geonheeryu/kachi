package me.rgunny.kachi.e2e.support

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * collector-service가 부르는 Google News RSS 검색(`GET /rss/search?q=`)을 흉내 내는 stub.
 *
 * 응답은 Google News RSS 2.0 문서이고 item의 description은 HTML 링크·매체명을 escape한 문자열이다.
 * `q`에 [publish]한 기사가 없으면 item 없는 문서를 돌려준다.
 */
class TestGoogleNewsServer : AutoCloseable {
    private val server = TestStubServer()
    private val articlesByQuery = ConcurrentHashMap<String, MutableList<TestNewsArticle>>()

    val port: Int get() = server.port

    init {
        server.route(RSS_SEARCH_PATH) { request ->
            val query = request.queryParameters["q"].orEmpty()
            TestStubResponse(
                statusCode = 200,
                body = rss(query, articlesByQuery[query].orEmpty()),
                contentType = "application/xml; charset=utf-8"
            )
        }
    }

    /** `q`가 [keyword]인 검색에 [articles]를 더한다. */
    fun publish(keyword: String, articles: List<TestNewsArticle>) {
        articlesByQuery.getOrPut(keyword) { CopyOnWriteArrayList() } += articles
    }

    override fun close() {
        server.close()
    }

    private fun rss(query: String, articles: List<TestNewsArticle>): String {
        val header = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
            <channel>
            <generator>NFE/5.0</generator>
            <title>"${escape(query)}" - Google 뉴스</title>
            <link>https://news.google.com/search?q=${escape(query)}&amp;hl=ko&amp;gl=KR&amp;ceid=KR:ko</link>
            <language>ko</language>
            <webMaster>news-webmaster@google.com</webMaster>
            <description>Google 뉴스</description>
        """.trimIndent()
        return (listOf(header) + articles.map { item(it) } + listOf("</channel>", "</rss>")).joinToString("\n")
    }

    private fun item(article: TestNewsArticle): String {
        val description = "<a href=\"${article.url}\" target=\"_blank\">${article.title}</a>&nbsp;&nbsp;" +
            "<font color=\"#6f6f6f\">${article.publisher}</font>"
        return """
            <item>
            <title>${escape(article.title)} - ${escape(article.publisher)}</title>
            <link>${escape(article.url)}</link>
            <guid isPermaLink="false">${escape(article.url)}</guid>
            <pubDate>${PUB_DATE.format(article.publishedAt.atOffset(ZoneOffset.UTC))}</pubDate>
            <description>${escape(description)}</description>
            <source url="https://${escape(article.publisher)}.example.com">${escape(article.publisher)}</source>
            </item>
        """.trimIndent()
    }

    private fun escape(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }

    private companion object {
        const val RSS_SEARCH_PATH = "/rss/search"
        val PUB_DATE: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME
    }
}
