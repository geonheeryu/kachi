package me.rgunny.kachi.notification.routing.application.service

import java.time.Instant

/**
 * RoutingJob eventKey를 만든다.
 *
 * 요약은 `{storyId}:{version}`, 격리는 `{quarantineId}:{quarantinedAt epoch millis}`다.
 */
object RoutingEventKey {

    fun forSummary(storyId: String, version: Long): String {
        return "$storyId:$version"
    }

    fun forQuarantine(quarantineId: String, quarantinedAt: Instant): String {
        return "$quarantineId:${quarantinedAt.toEpochMilli()}"
    }
}
