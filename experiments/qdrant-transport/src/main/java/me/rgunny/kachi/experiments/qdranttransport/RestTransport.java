package me.rgunny.kachi.experiments.qdranttransport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Qdrant REST API 전송. 공식 Java 클라이언트가 없어 JDK HttpClient와 Jackson으로 직접 쓴다.
 *
 * 요청·응답 본문 바이트는 UTF-8 길이다.
 */
public final class RestTransport implements Transport {

    private final Settings settings;
    private final HttpClient client;

    public RestTransport(Settings settings) {
        this.settings = settings;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public String name() {
        return "rest";
    }

    @Override
    public void recreateCollection(boolean withPayloadIndex) {
        deleteCollection();

        ObjectNode vectors = Jsonl.MAPPER.createObjectNode();
        vectors.put("size", settings.dimension());
        vectors.put("distance", "Cosine");
        vectors.put("datatype", "float32");
        ObjectNode create = Jsonl.MAPPER.createObjectNode();
        create.set("vectors", vectors);
        send(HttpRequest.newBuilder(collectionUri("")).PUT(body(create)), true);

        if (!withPayloadIndex) {
            return;
        }
        Map<String, String> schemas = Map.of("storyId", "keyword", "collectedAt", "integer", "language", "keyword");
        schemas.forEach((field, schema) -> {
            ObjectNode index = Jsonl.MAPPER.createObjectNode();
            index.put("field_name", field);
            index.put("field_schema", schema);
            send(HttpRequest.newBuilder(collectionUri("/index?wait=true")).PUT(body(index)), true);
        });
    }

    @Override
    public OpResult upsert(List<Point> batch) {
        ArrayNode points = Jsonl.MAPPER.createArrayNode();
        for (Point point : batch) {
            ObjectNode payload = Jsonl.MAPPER.createObjectNode();
            payload.put("storyId", point.storyId());
            payload.put("collectedAt", point.collectedAt());
            payload.put("language", point.language());
            ObjectNode node = Jsonl.MAPPER.createObjectNode();
            node.put("id", point.id().toString());
            ArrayNode vector = node.putArray("vector");
            for (float value : point.vector()) {
                vector.add(value);
            }
            node.set("payload", payload);
            points.add(node);
        }
        ObjectNode request = Jsonl.MAPPER.createObjectNode();
        request.set("points", points);

        byte[] bytes = bytes(request);
        HttpResponse<byte[]> response = send(
                HttpRequest.newBuilder(collectionUri("/points?wait=true")).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)),
                true);
        return new OpResult(batch.size(), bytes.length, response.body().length);
    }

    @Override
    public OpResult search(float[] vector, long collectedAfter, int topK) {
        ObjectNode range = Jsonl.MAPPER.createObjectNode();
        range.put("gte", collectedAfter);
        ObjectNode condition = Jsonl.MAPPER.createObjectNode();
        condition.put("key", "collectedAt");
        condition.set("range", range);
        ObjectNode filter = Jsonl.MAPPER.createObjectNode();
        filter.putArray("must").add(condition);

        ObjectNode request = Jsonl.MAPPER.createObjectNode();
        ArrayNode query = request.putArray("query");
        for (float value : vector) {
            query.add(value);
        }
        request.set("filter", filter);
        request.put("limit", topK);
        request.putArray("with_payload").add("storyId");

        byte[] bytes = bytes(request);
        HttpResponse<byte[]> response = send(
                HttpRequest.newBuilder(collectionUri("/points/query")).POST(HttpRequest.BodyPublishers.ofByteArray(bytes)),
                true);
        JsonNode points = Jsonl.MAPPER.readTree(response.body()).path("result").path("points");
        return new OpResult(points.size(), bytes.length, response.body().length);
    }

    @Override
    public void close() {
        client.close();
    }

    /**
     * 컬렉션이 없어질 때까지 기다린다. 지워지기 전에 다시 만들면 반복마다 다른 상태에서 재게 된다.
     */
    private void deleteCollection() {
        HttpResponse<byte[]> deleted = send(HttpRequest.newBuilder(collectionUri("")).DELETE(), false);
        if (deleted.statusCode() >= 300 && deleted.statusCode() != 404) {
            throw new IllegalStateException("DELETE " + collectionUri("") + " -> " + deleted.statusCode()
                    + " " + new String(deleted.body(), StandardCharsets.UTF_8));
        }
        for (int attempt = 0; attempt < 50; attempt++) {
            HttpResponse<byte[]> exists = send(HttpRequest.newBuilder(collectionUri("/exists")).GET(), true);
            if (!Jsonl.MAPPER.readTree(exists.body()).path("result").path("exists").asBoolean(true)) {
                return;
            }
            sleepBriefly();
        }
        throw new IllegalStateException("컬렉션 " + settings.collectionName() + "이 지워지지 않았다");
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("컬렉션 삭제 대기가 중단됐다", e);
        }
    }

    private URI collectionUri(String suffix) {
        return URI.create(settings.restBaseUrl() + "/collections/" + settings.collectionName() + suffix);
    }

    private HttpRequest.BodyPublisher body(ObjectNode node) {
        return HttpRequest.BodyPublishers.ofByteArray(bytes(node));
    }

    private byte[] bytes(ObjectNode node) {
        return Jsonl.MAPPER.writeValueAsString(node).getBytes(StandardCharsets.UTF_8);
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder builder, boolean failOnError) {
        HttpRequest request = builder.header("Content-Type", "application/json").build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (failOnError && response.statusCode() >= 300) {
                throw new IllegalStateException(request.method() + " " + request.uri() + " -> " + response.statusCode()
                        + " " + new String(response.body(), StandardCharsets.UTF_8));
            }
            return response;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("REST 호출이 중단됐다", e);
        }
    }
}
