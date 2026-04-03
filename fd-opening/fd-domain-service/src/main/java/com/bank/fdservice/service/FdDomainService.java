package com.bank.fdservice.service;

import com.bank.fdservice.adapter.CbsAdapter;
import com.bank.fdservice.model.FdApplication;
import com.bank.fdservice.validation.ValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Core FD Domain Service.
 *
 * Exposes three operations consumed by Job Workers (via internal REST API):
 *   1. validate()     – run all validations; persist INITIATED record
 *   2. persistDraft() – transition DBP state to DRAFT
 *   3. openInCbs()    – call CBS adapter; transition to COMPLETE
 */
@Service
public class FdDomainService {

    private static final Logger log = LoggerFactory.getLogger(FdDomainService.class);

    private final ValidationService       validationService;
    private final CbsAdapter              cbsAdapter;
    private final FdApplicationRepository repository;

    public FdDomainService(ValidationService validationService,
                            CbsAdapter cbsAdapter,
                            FdApplicationRepository repository) {
        this.validationService = validationService;
        this.cbsAdapter        = cbsAdapter;
        this.repository        = repository;
    }

    // ─── 1. Validate ──────────────────────────────────────────────────────

    public record ValidateResult(boolean valid, String reason, Map<String, Object> details) {}

    public Mono<ValidateResult> validate(String applicationId, Map<String, Object> payload) {
        log.info("Domain validate applicationId={}", applicationId);

        // Persist INITIATED record first (idempotent – upsert on duplicate)
        FdApplication app = buildApplication(applicationId, payload, FdApplication.FdStatus.INITIATED);

        return repository.insert(app)
            .then(validationService.validateAll(payload))
            .map(r -> new ValidateResult(r.valid(), r.reason(), r.details()))
            .doOnError(e -> log.error("Validate error applicationId={}", applicationId, e));
    }

    // ─── 2. Persist Draft ─────────────────────────────────────────────────

    public record DraftResult(String applicationId, String status) {}

    public Mono<DraftResult> persistDraft(String applicationId) {
        log.info("Domain persistDraft applicationId={}", applicationId);

        return repository.updateStatus(applicationId, FdApplication.FdStatus.DRAFT)
            .thenReturn(new DraftResult(applicationId, "DRAFT"))
            .doOnError(e -> log.error("PersistDraft error applicationId={}", applicationId, e));
    }

    // ─── 3. Open in CBS ───────────────────────────────────────────────────

    public record CbsOpenResult(
        String applicationId,
        String fdAccountNumber,
        String status,
        String cbsTransactionId
    ) {}

    public Mono<CbsOpenResult> openInCbs(String applicationId, Map<String, Object> payload) {
        log.info("Domain openInCbs applicationId={}", applicationId);

        return repository.updateStatus(applicationId, FdApplication.FdStatus.PROCESSING)
            .then(cbsAdapter.openFdAccount(applicationId, payload))
            .flatMap(cbsResult ->
                repository.updateComplete(
                    applicationId,
                    cbsResult.fdAccountNumber(),
                    cbsResult.cbsTransactionId()
                )
                .thenReturn(new CbsOpenResult(
                    applicationId,
                    cbsResult.fdAccountNumber(),
                    "COMPLETE",
                    cbsResult.cbsTransactionId()
                ))
            )
            .doOnError(e -> {
                log.error("CBS open error applicationId={}", applicationId, e);
                // Best-effort FAILED status update
                repository.updateStatus(applicationId, FdApplication.FdStatus.FAILED)
                    .subscribe();
            });
    }

    // ─── 4. Get application by ID ─────────────────────────────────────────

    public Mono<FdApplication> getApplicationById(String applicationId) {
        return repository.findById(applicationId);
    }

    // ─── helper ───────────────────────────────────────────────────────────

    private FdApplication buildApplication(String applicationId,
                                            Map<String, Object> payload,
                                            FdApplication.FdStatus status) {
        Instant now = Instant.now();
        return new FdApplication(
            applicationId,
            (String)  payload.get("customerId"),
            (String)  payload.get("sourceAccountNo"),
            (String)  payload.get("schemeCode"),
            (Integer) payload.get("tenureMonths"),
            new BigDecimal(payload.get("principalAmount").toString()),
            (String)  payload.get("currency"),
            (String)  payload.get("maturityInstruction"),
            (String)  payload.get("journeyType"),
            (String)  payload.get("channelId"),
            status,
            null, null, null,
            now, now
        );
    }
}