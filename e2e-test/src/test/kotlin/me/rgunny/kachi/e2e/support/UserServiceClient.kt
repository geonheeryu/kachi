package me.rgunny.kachi.e2e.support

import java.util.UUID

/**
 * user-service의 실제 API로 사용자·채널 바인딩·구독을 만든다. DB에 직접 넣지 않는다 — 주소 암호화와 바인딩 생명주기를 그대로 탄다.
 */
class UserServiceClient(
    private val baseUrl: String,
    private val http: JsonHttp,
    private val jwt: JwtSupport
) {

    /** 사용자를 등록하고 id와 그 사용자의 access token을 돌려준다. */
    fun registerUser(label: String): RegisteredUser {
        val suffix = UUID.randomUUID().toString().take(8)
        val response = http.post(
            "$baseUrl/api/v1/users",
            mapOf(
                "email" to "$label-$suffix@kachi.e2e",
                "nickname" to "$label-$suffix",
                "authProvider" to "LOCAL"
            )
        ).expect(201)
        val userId = response.data.path("id").asText()
        return RegisteredUser(userId = userId, accessToken = jwt.accessToken(userId))
    }

    fun bindSlack(user: RegisteredUser, path: String = UUID.randomUUID().toString()) {
        http.put(
            "$baseUrl/api/v1/me/channel-bindings/SLACK",
            mapOf("webhookUrl" to "https://hooks.slack.com/services/E2E/$path"),
            user.accessToken
        ).expect(200, 201)
    }

    fun bindDiscord(user: RegisteredUser, path: String = UUID.randomUUID().toString()) {
        http.put(
            "$baseUrl/api/v1/me/channel-bindings/DISCORD",
            mapOf("webhookUrl" to "https://discord.com/api/webhooks/000000/$path"),
            user.accessToken
        ).expect(200, 201)
    }

    /** Telegram은 링크 발급 -> 봇이 chat id를 연결하는 두 단계다. 두 번째 단계는 봇이 부르는 internal API를 그대로 부른다. */
    fun bindTelegram(user: RegisteredUser, chatId: String = (100_000_000L + (Math.random() * 899_999_999).toLong()).toString()) {
        val link = http.put("$baseUrl/api/v1/me/channel-bindings/TELEGRAM", bearer = user.accessToken).expect(200)
        val token = link.data.path("linkUrl").asText().substringAfter("?start=")
        check(token.isNotBlank()) { "telegram link token이 없다: ${link.rawBody}" }
        http.post(
            "$baseUrl/api/v1/internal/channel-bindings/telegram/link",
            mapOf("token" to token, "chatId" to chatId)
        ).expect(204)
    }

    fun subscribe(user: RegisteredUser, keyword: String, channels: Set<String>) {
        http.post(
            "$baseUrl/api/v1/me/keywords",
            mapOf("name" to keyword, "channels" to channels),
            user.accessToken
        ).expect(200, 201)
    }

    /** 관리자와 그들이 바인딩한 채널. routing이 격리 알림 수신자를 고를 때 부르는 것과 같은 API다. */
    fun admins(): Map<String, List<String>> {
        val response = http.get("$baseUrl/api/v1/internal/users?role=ADMIN").expect(200)
        return response.data.associate { node ->
            val channelsNode = node.path("channels")
            val channels: List<String> = (0 until channelsNode.size()).map { channelsNode.get(it).asText() }
            node.path("userId").asText() to channels
        }
    }
}

/**
 * 등록된 사용자 id와 그 사용자로 서명된 access token.
 */
data class RegisteredUser(val userId: String, val accessToken: String)
