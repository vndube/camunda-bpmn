package com.bank.jobworker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * Reactive HTTP client from Job Worker → FD Domain Service.
 * All calls are non-blocking and return Mono.
 */
@Service
public class FdDomainClient {

    private static final Logger log = LoggerFactory.getLogger(FdDomainClient.class);

    private final WebClient webClient;
    private final Duration  timeout;

    public FdDomainClient(
            WebClient.Builder builder,
            @Value("${fd-domain.base-url}") String baseUrl,
            @Value("${fd-domain.timeout-seconds:20}") int timeoutSec) {
        this.webClient = builder
            .baseUrl(baseUrl)
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .build();
        this.timeout = Duration.ofSeconds(timeoutSec);
    }

    // ── Validate FD ──────────────────────────────────────────────────────

    public record ValidationResult(boolean valid, String reason, Map<String, Object> details) {}

    public Mono<ValidationResult> validateFd(String applicationId, Map<String, Object> payload) {
        log.info("Calling domain validation applicationId={}", applicationId);
        return webClient.post()
            .uri("/internal/fd/validate")
            .bodyValue(payload)
            .retrieve()
            // Handle HTTP errors reactively if needed, otherwise bodyToMono handles 4xx/5xx
            .bodyToMono(ValidationResult.class)
            .timeout(timeout)
            .doOnError(e -> log.error("Domain validate call failed applicationId={}", applicationId, e));
            // Removed .block()
    }

    // ── Persist draft ─────────────────────────────────────────────────────

    public record PersistDraftResult(String applicationId, String status) {}

    public Mono<PersistDraftResult> persistDraft(String applicationId) {
        log.info("Calling domain persist draft applicationId={}", applicationId);
        return webClient.post()
            .uri("/internal/fd/{id}/draft", applicationId)
            .retrieve()
            .bodyToMono(PersistDraftResult.class)
            .timeout(timeout);
            // Removed .block()
    }

    // ── Open in CBS ───────────────────────────────────────────────────────

    public record CbsOpenResult(
        String applicationId,
        String fdAccountNumber,
        String status,
        String cbsTransactionId
    ) {} 

    public Mono<CbsOpenResult> openInCbs(String applicationId, Map<String, Object> payload) {
        log.info("Calling domain CBS open applicationId={}", applicationId);
        return webClient.post()
            .uri("/internal/fd/cbs-open")
            .bodyValue(payload)
            .retrieve()
            .bodyToMono(CbsOpenResult.class)
            .timeout(timeout)
            .doOnError(e -> log.error("Domain CBS open call failed applicationId={}", applicationId, e));
            // Removed .block()
    }
}