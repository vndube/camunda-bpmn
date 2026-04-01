package com.bank.mobilebff.service;

import com.bank.mobilebff.model.FdOpenRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;

/**
 * Persists and fetches full FD payload in S3.
 * Key format:  fd-requests/{applicationId}.json
 *
 * Reactive wrapper: blocking AWS SDK calls run on boundedElastic scheduler.
 */
@Service
public class S3PayloadService {

    private static final Logger log = LoggerFactory.getLogger(S3PayloadService.class);

    private final S3AsyncClient s3Client;
    private final ObjectMapper  objectMapper;

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    @Value("${aws.s3.prefix:fd-requests/}")
    private String prefix;

    public S3PayloadService(S3AsyncClient s3Client, ObjectMapper objectMapper) {
        this.s3Client    = s3Client;
        this.objectMapper = objectMapper;
    }

    /**
     * Serialize FdOpenRequest and put to S3.
     * Returns the S3 key on success.
     */
    public Mono<String> persistPayload(FdOpenRequest request) {
        String key = buildKey(request.applicationId());
        return Mono.fromCallable(() -> serialize(request))
            .flatMap(json -> putToS3(key, json))
            .doOnSuccess(k -> log.info("Payload persisted to S3 key={}", k))
            .doOnError(e  -> log.error("S3 persist failed for applicationId={}", request.applicationId(), e));
    }

    // ── private helpers ────────────────────────────────────────────────

    private String buildKey(String applicationId) {
        return prefix + applicationId + ".json";
    }

    private String serialize(FdOpenRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Serialization failed for applicationId=" + request.applicationId(), e);
        }
    }

    private Mono<String> putToS3(String key, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        PutObjectRequest putReq = PutObjectRequest.builder()
            .bucket(bucketName)
            .key(key)
            .contentType("application/json")
            .contentLength((long) bytes.length)
            .serverSideEncryption("aws:kms")   // enforce encryption at rest
            .build();

        return Mono.fromFuture(
                s3Client.putObject(putReq, AsyncRequestBody.fromBytes(bytes))
            )
            .subscribeOn(Schedulers.boundedElastic())
            .thenReturn(key);
    }
}
