package me.rgunny.kachi.ai.adapter.outbound.persistence.run

import java.time.Instant
import java.util.UUID
import me.rgunny.kachi.ai.domain.llm.LlmProvider
import me.rgunny.kachi.ai.domain.llm.PromptVersion
import me.rgunny.kachi.ai.domain.run.AiFailureReason
import me.rgunny.kachi.ai.domain.run.AiRun
import me.rgunny.kachi.ai.domain.run.AiRunId
import me.rgunny.kachi.ai.domain.run.AiRunStatus
import me.rgunny.kachi.ai.domain.run.AiRunTargetType
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "ai_runs")
data class AiRunMongoDocument(
    @Id
    val id: UUID,
    val targetType: AiRunTargetType,
    @Indexed
    val status: AiRunStatus,
    @Indexed
    val startedAt: Instant,
    val finishedAt: Instant?,
    val requestedKeywords: Int,
    val succeededCount: Int,
    val failureCount: Int,
    val failureReason: AiFailureReason?,
    val provider: String?,
    val model: String?,
    val promptVersion: String?
) {

    fun toDomain(): AiRun {
        return AiRun.restore(
            id = AiRunId.of(id),
            targetType = targetType,
            status = status,
            startedAt = startedAt,
            finishedAt = finishedAt,
            requestedKeywords = requestedKeywords,
            succeededCount = succeededCount,
            failureCount = failureCount,
            failureReason = failureReason,
            provider = provider?.let(LlmProvider::ofCode),
            model = model,
            promptVersion = promptVersion?.let(PromptVersion::of)
        )
    }

    companion object {
        fun fromDomain(aiRun: AiRun): AiRunMongoDocument {
            return AiRunMongoDocument(
                id = aiRun.id.value,
                targetType = aiRun.targetType,
                status = aiRun.status,
                startedAt = aiRun.startedAt,
                finishedAt = aiRun.finishedAt,
                requestedKeywords = aiRun.requestedKeywords,
                succeededCount = aiRun.succeededCount,
                failureCount = aiRun.failureCount,
                failureReason = aiRun.failureReason,
                provider = aiRun.provider?.code,
                model = aiRun.model,
                promptVersion = aiRun.promptVersion?.value
            )
        }
    }
}
