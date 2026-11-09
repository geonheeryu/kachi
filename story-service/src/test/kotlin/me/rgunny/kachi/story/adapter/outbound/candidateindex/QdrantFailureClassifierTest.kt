package me.rgunny.kachi.story.adapter.outbound.candidateindex

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import me.rgunny.kachi.story.domain.index.CandidateIndexFailureCode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@DisplayName("QdrantFailureClassifier")
class QdrantFailureClassifierTest {

    @ParameterizedTest
    @CsvSource(
        "UNAVAILABLE, INDEX_UNAVAILABLE",
        "DEADLINE_EXCEEDED, INDEX_TIMEOUT",
        "RESOURCE_EXHAUSTED, INDEX_OVERLOADED",
        "INTERNAL, INDEX_SERVER_ERROR",
        "UNKNOWN, INDEX_SERVER_ERROR",
        "ABORTED, INDEX_SERVER_ERROR",
        "NOT_FOUND, INDEX_COLLECTION_MISSING",
        "INVALID_ARGUMENT, INDEX_REQUEST_REJECTED",
        "FAILED_PRECONDITION, INDEX_PRECONDITION_FAILED",
        "UNAUTHENTICATED, INDEX_UNAUTHORIZED",
        "PERMISSION_DENIED, INDEX_UNAUTHORIZED",
        "CANCELLED, INDEX_UNKNOWN_ERROR",
        "UNIMPLEMENTED, INDEX_UNKNOWN_ERROR"
    )
    @DisplayName("gRPC status마다 실패 코드가 표대로다")
    fun codePerStatus(status: Status.Code, expected: CandidateIndexFailureCode) {
        val failure = QdrantFailureClassifier.classify(StatusRuntimeException(status.toStatus()))

        assertEquals(expected, failure.code)
        assertEquals(status.name, failure.grpcStatus)
    }

    @Test
    @DisplayName("status의 description이 있으면 메시지로 쓰고, 없으면 코드 기본 메시지다")
    fun messageFromDescription() {
        val described = QdrantFailureClassifier.classify(
            StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Wrong input: vector size"))
        )
        val bare = QdrantFailureClassifier.classify(StatusRuntimeException(Status.UNAVAILABLE))

        assertEquals("Wrong input: vector size", described.message)
        assertEquals(CandidateIndexFailureCode.INDEX_UNAVAILABLE.defaultMessage, bare.message)
    }

    @Test
    @DisplayName("예외 체인 안쪽의 gRPC status도 찾는다")
    fun statusInsideCause() {
        val wrapped = IllegalStateException("wrapped", StatusException(Status.DEADLINE_EXCEEDED))

        assertEquals(CandidateIndexFailureCode.INDEX_TIMEOUT, QdrantFailureClassifier.classify(wrapped).code)
    }

    @Test
    @DisplayName("gRPC 밖의 예외는 UNKNOWN이고 status가 없다")
    fun nonGrpcException() {
        val failure = QdrantFailureClassifier.classify(IOException("connection reset"))

        assertEquals(CandidateIndexFailureCode.INDEX_UNKNOWN_ERROR, failure.code)
        assertEquals("connection reset", failure.message)
        assertNull(failure.grpcStatus)
    }
}
