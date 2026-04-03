package com.bank.fdservice.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * Validates source savings account:
 *   - Account status must be ACTIVE
 *   - Available balance must be >= requested principal
 *
 * Calls internal Account Service.
 */
@Component
public class SourceAccountValidator {

    private static final Logger log = LoggerFactory.getLogger(SourceAccountValidator.class);

    private final WebClient webClient;

    public SourceAccountValidator(
            WebClient.Builder builder,
            @Value("${account.service.base-url:http://account-service:8084}") String baseUrl) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    public Mono<ValidationService.ValidationResult> validate(String accountNo,
                                                              BigDecimal requiredAmount) {
        log.debug("Source account check accountNo={} required={}", accountNo, requiredAmount);

        return webClient.get()
            .uri("/internal/accounts/{no}/summary", accountNo)
            .retrieve()
            .bodyToMono(AccountSummary.class)
            .timeout(Duration.ofSeconds(10))
            .map(acc -> {
                if (!"ACTIVE".equalsIgnoreCase(acc.status())) {
                    return ValidationService.ValidationResult.fail(
                        "Source account not active. Status: " + acc.status());
                }
                if (acc.availableBalance().compareTo(requiredAmount) < 0) {
                    return ValidationService.ValidationResult.fail(
                        "Insufficient balance. Available: " + acc.availableBalance()
                        + " Required: " + requiredAmount);
                }
                return ValidationService.ValidationResult.pass(Map.of(
                    "accountStatus",    acc.status(),
                    "availableBalance", acc.availableBalance()
                ));
            })
            .onErrorMap(e -> new RuntimeException("Account validation error: " + e.getMessage(), e));
    }

    private record AccountSummary(
        String     accountNo,
        String     status,
        BigDecimal availableBalance,
        String     currency
    ) {}
}
