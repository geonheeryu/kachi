package me.rgunny.kachi.e2e.support

import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher

/**
 * 테스트가 실패하면 서비스 컨테이너 로그의 끝을 표준 출력에 남기는 JUnit extension.
 *
 * 표준 출력은 테스트 결과 XML의 `system-out`에 실린다.
 */
class ServiceLogsOnFailure : TestWatcher {

    override fun testFailed(context: ExtensionContext, cause: Throwable?) {
        val logs = runCatching { KachiCluster.shared().serviceLogTails(LINES_PER_SERVICE) }
            .getOrElse { "cluster unavailable: ${it.message}" }
        println("Service logs after failure of ${context.displayName}\n$logs")
    }

    private companion object {
        const val LINES_PER_SERVICE = 120
    }
}
