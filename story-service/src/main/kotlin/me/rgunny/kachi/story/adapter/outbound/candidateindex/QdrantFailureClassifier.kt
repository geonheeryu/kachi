package me.rgunny.kachi.story.adapter.outbound.candidateindex

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import me.rgunny.kachi.story.domain.index.CandidateIndexFailure
import me.rgunny.kachi.story.domain.index.CandidateIndexFailureCode

/** Qdrant 호출 중 발생한 예외를 gRPC status로 실패 코드에 대응시키는 classifier. */
object QdrantFailureClassifier {

    fun classify(exception: Throwable): CandidateIndexFailure {
        val status = grpcStatusOf(exception)
            ?: return CandidateIndexFailure(
                code = CandidateIndexFailureCode.INDEX_UNKNOWN_ERROR,
                message = exception.message ?: CandidateIndexFailureCode.INDEX_UNKNOWN_ERROR.defaultMessage
            )
        val code = when (status.code) {
            Status.Code.UNAVAILABLE -> CandidateIndexFailureCode.INDEX_UNAVAILABLE
            Status.Code.DEADLINE_EXCEEDED -> CandidateIndexFailureCode.INDEX_TIMEOUT
            Status.Code.RESOURCE_EXHAUSTED -> CandidateIndexFailureCode.INDEX_OVERLOADED
            Status.Code.INTERNAL, Status.Code.UNKNOWN, Status.Code.ABORTED -> CandidateIndexFailureCode.INDEX_SERVER_ERROR
            Status.Code.NOT_FOUND -> CandidateIndexFailureCode.INDEX_COLLECTION_MISSING
            Status.Code.INVALID_ARGUMENT -> CandidateIndexFailureCode.INDEX_REQUEST_REJECTED
            Status.Code.FAILED_PRECONDITION -> CandidateIndexFailureCode.INDEX_PRECONDITION_FAILED
            Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED -> CandidateIndexFailureCode.INDEX_UNAUTHORIZED
            else -> CandidateIndexFailureCode.INDEX_UNKNOWN_ERROR
        }

        return CandidateIndexFailure(
            code = code,
            message = status.description?.takeIf { it.isNotBlank() } ?: code.defaultMessage,
            grpcStatus = status.code.name
        )
    }

    private fun grpcStatusOf(exception: Throwable): Status? {
        var current: Throwable? = exception
        while (current != null) {
            when (current) {
                is StatusRuntimeException -> return current.status
                is StatusException -> return current.status
            }
            current = current.cause
        }
        return null
    }
}
