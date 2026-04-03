package com.bank.jobworker.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

@Configuration
public class JobWorkerConfiguration {

    // ── Zeebe client ──────────────────────────────────────────────────────
    // The Spring-Zeebe SDK automatically configures the ZeebeClient bean
    // based on properties starting with 'zeebe.client.*' in application.yaml.
    // No explicit bean definition is needed here unless customizing beyond properties.


    // ── WebClient builder (shared) ───────────────────────────────────────
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    // ── AWS S3 Async client ──────────────────────────────────────────────
    @Value("${aws.s3.region:ap-south-1}")
    private String awsRegion;

    @Value("${aws.credentials.access-key:}")
    private String accessKey;

    @Value("${aws.credentials.secret-key:}")
    private String secretKey;

    /**
     * S3AsyncClient for non-blocking S3 operations.
     */
    @Bean
    public S3AsyncClient s3AsyncClient() {
        var builder = S3AsyncClient.builder()
            .region(Region.of(awsRegion));

        if (!accessKey.isBlank()) {
            builder.credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }

    // ── ObjectMapper (Optimized for WebFlux/Zeebe) ────────────────────────
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            // 1. Handle Java 8 Dates as ISO-8601 strings
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // 2. Reduce payload size by ignoring nulls
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            // 3. Resilience: Don't fail if DTO is missing fields found in JSON
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}