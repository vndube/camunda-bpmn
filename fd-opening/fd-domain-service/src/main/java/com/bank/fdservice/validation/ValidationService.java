package com.bank.fdservice.validation;

import java.math.BigDecimal;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

/**
 * Orchestrates all three FD validations in sequence:
 *   1. KYC + customer eligibility
 *   2. Source account eligibility (status + balance)
 *   3. FD scheme eligibility (tenure, amount, type)
 *
 * Each check is a reactive call to an appropriate downstream service.
 * The result carries a pass/fail flag, reason, and details map.
 */
@Service
public class ValidationService {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);

    private final KycEligibilityValidator    kycValidator;
    private final SourceAccountValidator     accountValidator;
    private final FdSchemeValidator          schemeValidator;

    public ValidationService(KycEligibilityValidator kycValidator,
                              SourceAccountValidator accountValidator,
                              FdSchemeValidator schemeValidator) {
        this.kycValidator    = kycValidator;
        this.accountValidator = accountValidator;
        this.schemeValidator  = schemeValidator;
    }

    public record ValidationResult(boolean valid, String reason, Map<String, Object> details) {
        public static ValidationResult pass(Map<String, Object> details) {
            return new ValidationResult(true, null, details);
        }
        public static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason, Map.of());
        }
    }

    /**
     * Run all validations sequentially.
     * Short-circuits on first failure.
     */
    public Mono<ValidationResult> validateAll(Map<String, Object> payload) {
        String customerId     = (String) payload.get("customerId");
        String sourceAccount  = (String) payload.get("sourceAccountNo");
        String schemeCode     = (String) payload.get("schemeCode");
        Integer tenure        = (Integer) payload.get("tenureMonths");
        BigDecimal principal  = new BigDecimal(payload.get("principalAmount").toString());

        log.info("Starting FD validations customerId={} scheme={}", customerId, schemeCode);

        return kycValidator.validate(customerId)
            .flatMap(kycResult -> {
                if (!kycResult.valid()) return Mono.just(kycResult);
                return accountValidator.validate(sourceAccount, principal);
            })
            .flatMap(accResult -> {
                if (!accResult.valid()) return Mono.just(accResult);
                return schemeValidator.validate(schemeCode, tenure, principal);
            })
            .doOnSuccess(r -> log.info("Validation complete valid={} reason={}", r.valid(), r.reason()));
    }
}
