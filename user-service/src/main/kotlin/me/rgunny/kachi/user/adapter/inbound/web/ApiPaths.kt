package me.rgunny.kachi.user.adapter.inbound.web

/**
 * user-service HTTP API Path Contract.
 *
 * Spring Framework 7 API Versioning을 기준으로 버전별 경로를 구성한다.
 * Controller mapping과 Security matcher가 같은 경로를 참조하도록 관리한다.
 */
object ApiPaths {
    const val AUTH_TOKEN_REFRESH = "/auth/token/refresh"
    const val AUTH_LOGOUT = "/auth/logout"
    const val USERS = "/users"
    const val ME = "/me"
    const val ME_KEYWORDS = "/me/keywords"
    const val ME_KEYWORD = "/me/keywords/{subscriptionId}"
    const val ME_CHANNEL_BINDINGS = "/me/channel-bindings"
    const val ME_CHANNEL_BINDING = "/me/channel-bindings/{channel}"
    const val INTERNAL_ACTIVE_KEYWORDS = "/internal/keywords/active"
    const val INTERNAL_SUBSCRIPTION_RECIPIENTS_SEARCH = "/internal/subscriptions/recipients/search"
    const val INTERNAL_USERS = "/internal/users"
    const val INTERNAL_CHANNEL_BINDING = "/internal/users/{userId}/channel-bindings/{channel}"
    const val INTERNAL_TELEGRAM_LINK = "/internal/channel-bindings/telegram/link"
    const val INTERNAL_ANY = "/internal/**"

    const val V1_AUTH_TOKEN_REFRESH = "${ApiVersions.V1_PATH_PREFIX}$AUTH_TOKEN_REFRESH"
    const val V1_AUTH_LOGOUT = "${ApiVersions.V1_PATH_PREFIX}$AUTH_LOGOUT"
    const val V1_USERS = "${ApiVersions.V1_PATH_PREFIX}$USERS"
    const val V1_ME = "${ApiVersions.V1_PATH_PREFIX}$ME"
    const val V1_ME_KEYWORDS = "${ApiVersions.V1_PATH_PREFIX}$ME_KEYWORDS"
    const val V1_ME_KEYWORD = "${ApiVersions.V1_PATH_PREFIX}$ME_KEYWORD"
    const val V1_ME_CHANNEL_BINDINGS = "${ApiVersions.V1_PATH_PREFIX}$ME_CHANNEL_BINDINGS"
    const val V1_ME_CHANNEL_BINDING = "${ApiVersions.V1_PATH_PREFIX}$ME_CHANNEL_BINDING"
    const val V1_INTERNAL_ACTIVE_KEYWORDS = "${ApiVersions.V1_PATH_PREFIX}$INTERNAL_ACTIVE_KEYWORDS"
    const val V1_INTERNAL_SUBSCRIPTION_RECIPIENTS_SEARCH =
        "${ApiVersions.V1_PATH_PREFIX}$INTERNAL_SUBSCRIPTION_RECIPIENTS_SEARCH"
    const val V1_INTERNAL_USERS = "${ApiVersions.V1_PATH_PREFIX}$INTERNAL_USERS"
    const val V1_INTERNAL_CHANNEL_BINDING = "${ApiVersions.V1_PATH_PREFIX}$INTERNAL_CHANNEL_BINDING"
    const val V1_INTERNAL_TELEGRAM_LINK = "${ApiVersions.V1_PATH_PREFIX}$INTERNAL_TELEGRAM_LINK"
    const val V1_INTERNAL_ANY = "${ApiVersions.V1_PATH_PREFIX}$INTERNAL_ANY"
}
