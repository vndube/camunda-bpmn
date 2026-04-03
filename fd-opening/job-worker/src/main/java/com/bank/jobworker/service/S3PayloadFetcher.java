package com.bank.jobworker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.IOException;
import java.util.Map;

/**
 * Fetches full FD payload from S3 reactively.
 * Uses S3AsyncClient to ensure non-blocking I/O.
 */
@Service
public class S3PayloadFetcher {

    private static final Logger log = LoggerFactory.getLogger(S3PayloadFetcher.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final S3AsyncClient s3Client; // Switched to Async
    private final ObjectMapper objectMapper;

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    @Value("${aws.s3.prefix:fd-requests/}")
    private String prefix;

    public S3PayloadFetcher(S3AsyncClient s3Client, ObjectMapper objectMapper) {
        this.s3Client     = s3Client;
        this.objectMapper = objectMapper;
    }

    /**
     * Fetch and deserialize the FD payload from S3 reactively.
     * Key: {prefix}{applicationId}.json
     */
    public Mono<Map<String, Object>> fetchPayload(String applicationId) {
        String key = prefix + applicationId + ".json";
        log.debug("Fetching payload from S3 key={}", key);

        GetObjectRequest req = GetObjectRequest.builder()
            .bucket(bucketName)
            .key(key)
            .build();

        // 1. Call S3 Async, converting CompletableFuture to Mono
        return Mono.fromFuture(() -> s3Client.getObject(req, AsyncResponseTransformer.toBytes()))
            // 2. Map the bytes to JSON Map
            .map(responseBytes -> {
                try {
                    Map<String, Object> payload = objectMapper.readValue(responseBytes.asByteArray(), MAP_TYPE);
                    log.info("Payload fetched from S3 applicationId={}", applicationId);
                    return payload;
                } catch (IOException e) {
                    throw new RuntimeException("Failed to parse JSON from S3 key=" + key, e);
                }
            })
            // 3. Handle errors reactively
            .onErrorMap(e -> new RuntimeException("Failed to fetch payload from S3 key=" + key, e));
    }
}