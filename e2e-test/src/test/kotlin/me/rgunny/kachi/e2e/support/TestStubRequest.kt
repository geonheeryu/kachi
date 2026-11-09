package me.rgunny.kachi.e2e.support

/**
 * [TestStubServer]가 받은 HTTP 요청 하나.
 *
 * [queryParameters]는 URL 디코딩한 값이며 같은 이름이 여럿이면 첫 값이다.
 */
data class TestStubRequest(
    val path: String,
    val queryParameters: Map<String, String>,
    val body: String
)
