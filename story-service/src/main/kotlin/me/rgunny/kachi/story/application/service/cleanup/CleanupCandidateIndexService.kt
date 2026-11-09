package me.rgunny.kachi.story.application.service.cleanup

import java.time.Clock
import java.time.Instant
import me.rgunny.kachi.story.application.port.inbound.cleanup.CleanupCandidateIndexUseCase
import me.rgunny.kachi.story.application.port.inbound.cleanup.model.CleanupCandidateIndexResult
import me.rgunny.kachi.story.application.port.outbound.candidateindex.CandidateIndexPort
import me.rgunny.kachi.story.application.service.assembly.AssemblyPolicy
import org.springframework.stereotype.Service

/**
 * 후보 검색 창을 지난 기사 벡터를 색인에서 지우는 유스케이스.
 *
 * 보관 창은 [AssemblyPolicy.candidateWindow]와 같은 값이다.
 */
@Service
class CleanupCandidateIndexService(
    private val candidateIndexPort: CandidateIndexPort,
    private val assemblyPolicy: AssemblyPolicy,
    private val clock: Clock
) : CleanupCandidateIndexUseCase {

    override suspend fun cleanup(): CleanupCandidateIndexResult {
        val threshold = Instant.now(clock).minus(assemblyPolicy.candidateWindow)
        candidateIndexPort.deleteCollectedBefore(threshold)

        return CleanupCandidateIndexResult(threshold = threshold)
    }
}
