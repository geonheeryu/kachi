package me.rgunny.kachi.ai.config

import me.rgunny.kachi.ai.adapter.outbound.lock.InMemoryExecutionLockAdapter
import me.rgunny.kachi.ai.adapter.outbound.lock.RoutingExecutionLockAdapter
import me.rgunny.kachi.ai.application.port.outbound.lock.ExecutionLockPort
import me.rgunny.kachi.ai.application.port.outbound.lock.model.ExecutionLockScope
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * 실행 lock의 범위를 구현에 연결하는 설정.
 *
 * 두 범위 모두 이 인스턴스 안에서만 유효한 lock으로 간다.
 * 여러 인스턴스를 띄우면 CLUSTER 범위의 작업이 인스턴스마다 따로 실행되므로, 그 시점에는 이 연결을 바꿔야 한다.
 * 바뀌는 것은 여기 한 곳이고 executor와 진입점은 그대로다.
 */
@Configuration
class ExecutionLockConfig {

    @Bean
    fun executionLockPort(clock: Clock): ExecutionLockPort {
        val inMemoryLock = InMemoryExecutionLockAdapter(clock)

        return RoutingExecutionLockAdapter(
            delegates = mapOf(
                ExecutionLockScope.INSTANCE to inMemoryLock,
                ExecutionLockScope.CLUSTER to inMemoryLock
            )
        )
    }
}
