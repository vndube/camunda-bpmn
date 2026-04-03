package com.bank.mobilebff.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.camunda.zeebe.client.ZeebeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import java.net.URI;

@Configuration
public class BffConfiguration {

    // ── Zeebe client ──────────────────────────────────────────────────────
    // gateway-address format: host:port  e.g. localhost:26500
    @Value("${zeebe.client.broker.gateway-address}")
    private String zeebeGateway;

    @Value("${zeebe.client.security.plaintext:true}")
    private boolean plaintext;

    /**
     * ZeebeClient for Zeebe 8.5.x
     *
     * grpcAddress requires a full URI:
     *   plaintext  → http://localhost:26500
     *   TLS        → https://zeebe-host:26500
     *
     * usePlaintext() must be called explicitly for non-TLS connections.
     */
//    @Bean
//    public ZeebeClient zeebeClient() {
//        String scheme = plaintext ? "http" : "https";
//        URI grpcUri   = URI.create(scheme + "://" + zeebeGateway);
//
//        var builder = ZeebeClient.newClientBuilder()
//            .grpcAddress(grpcUri);
//
//        if (plaintext) {
//            builder.usePlaintext();
//        }
//
//        return builder.build();
//    }

    // ── WebClient builder (shared — injected into ZeebeClientService) ──────
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    // ── AWS S3 Async client ───────────────────────────────────────────────
    @Value("${aws.s3.region:ap-south-1}")
    private String awsRegion;

    @Value("${aws.credentials.access-key:}")
    private String accessKey;

    @Value("${aws.credentials.secret-key:}")
    private String secretKey;

    @Bean
    public S3AsyncClient s3AsyncClient() {
        var builder = S3AsyncClient.builder()
            .region(Region.of(awsRegion));

        if (!accessKey.isBlank()) {
            builder.credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            // IAM role / env vars / ~/.aws/credentials in prod
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }

    // ── ObjectMapper ──────────────────────────────────────────────────────
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
