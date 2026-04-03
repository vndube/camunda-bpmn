package com.bank.fdservice.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * Validates customer KYC status and eligibility for FD opening.
 *
 * Calls internal Customer/KYC service. Expected response:
 * {
 *   "kycStatus": "VERIFIED | PENDING | EXPIRED",
 *   "eligible":  true | false,
 *   "reason":    "..." // only on failure
 * }
 */
@Component
public class KycEligibilityValidator {

    private static final Logger log = LoggerFactory.getLogger(KycEligibilityValidator.class);

    private final WebClient webClient;

    public KycEligibilityValidator(
            WebClient.Builder builder,
            @Value("${kyc.service.base-url:http://kyc-service:8083}") String baseUrl) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    public Mono<ValidationService.ValidationResult> validate(String customerId) {
        log.debug("KYC check customerId={}", customerId);

        return webClient.get()
            .uri("/internal/customers/{id}/kyc-eligibility", customerId)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, resp ->
                Mono.error(new IllegalStateException("KYC service 4xx for customerId=" + customerId)))
            .bodyToMono(KycResponse.class)
            .timeout(Duration.ofSeconds(10))
            .map(resp -> {
                if (!"VERIFIED".equals(resp.kycStatus())) {
                    return ValidationService.ValidationResult.fail(
                        "KYC not verified. Status: " + resp.kycStatus());
                }
                if (!resp.eligible()) {
                    return ValidationService.ValidationResult.fail(
                        "Customer not eligible: " + resp.reason());
                }
                return ValidationService.ValidationResult.pass(
                    Map.of("kycStatus", resp.kycStatus()));
            })
            .onErrorMap(e -> new RuntimeException("KYC validation error: " + e.getMessage(), e));
    }

    private record KycResponse(String kycStatus, boolean eligible, String reason) {}
}
