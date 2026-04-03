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
 * Validates FD scheme eligibility:
 *   - Scheme must be active
 *   - Tenure must be within allowed min/max for scheme
 *   - Principal must be within allowed min/max for scheme
 *
 * Calls internal Product/Scheme Service.
 */
@Component
public class FdSchemeValidator {

    private static final Logger log = LoggerFactory.getLogger(FdSchemeValidator.class);

    private final WebClient webClient;

    public FdSchemeValidator(
            WebClient.Builder builder,
            @Value("${scheme.service.base-url:http://scheme-service:8085}") String baseUrl) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    public Mono<ValidationService.ValidationResult> validate(String schemeCode,
                                                              int tenureMonths,
                                                              BigDecimal principal) {
        log.debug("Scheme check schemeCode={} tenure={} principal={}", schemeCode, tenureMonths, principal);

        return webClient.get()
            .uri("/internal/schemes/{code}", schemeCode)
            .retrieve()
            .bodyToMono(SchemeDetails.class)
            .timeout(Duration.ofSeconds(10))
            .map(scheme -> {
                if (!"ACTIVE".equalsIgnoreCase(scheme.status())) {
                    return ValidationService.ValidationResult.fail(
                        "FD scheme not active: " + schemeCode);
                }
                if (tenureMonths < scheme.minTenureMonths()
                        || tenureMonths > scheme.maxTenureMonths()) {
                    return ValidationService.ValidationResult.fail(
                        "Tenure " + tenureMonths + "m outside allowed range ["
                        + scheme.minTenureMonths() + ", " + scheme.maxTenureMonths() + "]");
                }
                if (principal.compareTo(scheme.minAmount()) < 0
                        || principal.compareTo(scheme.maxAmount()) > 0) {
                    return ValidationService.ValidationResult.fail(
                        "Principal " + principal + " outside scheme amount range ["
                        + scheme.minAmount() + ", " + scheme.maxAmount() + "]");
                }
                return ValidationService.ValidationResult.pass(Map.of(
                    "schemeCode",       schemeCode,
                    "interestRate",     scheme.interestRate(),
                    "minTenureMonths",  scheme.minTenureMonths(),
                    "maxTenureMonths",  scheme.maxTenureMonths()
                ));
            })
            .onErrorMap(e -> new RuntimeException("Scheme validation error: " + e.getMessage(), e));
    }

    private record SchemeDetails(
        String     schemeCode,
        String     status,
        int        minTenureMonths,
        int        maxTenureMonths,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        BigDecimal interestRate
    ) {}
}
