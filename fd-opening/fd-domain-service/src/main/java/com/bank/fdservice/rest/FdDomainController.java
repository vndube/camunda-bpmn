package com.bank.fdservice.rest;


import com.bank.fdservice.model.FdApplication;
import com.bank.fdservice.service.FdDomainService;
import com.bank.fdservice.service.FdDomainService.CbsOpenResult;
import com.bank.fdservice.service.FdDomainService.DraftResult;
import com.bank.fdservice.service.FdDomainService.ValidateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Internal REST API for FD Domain Service.
 * Consumed exclusively by Zeebe Job Workers — not exposed externally.
 * Base path: /internal  (configured via spring.webflux.base-path in application.yml)
 *
 * Endpoints:
 *   POST /internal/fd/validate          – run all three validations
 *   POST /internal/fd/{id}/draft        – transition DBP state → DRAFT
 *   POST /internal/fd/cbs-open          – open FD in CBS → COMPLETE
 *   GET  /internal/fd/{id}              – fetch application state by applicationId
 */
@RestController
@RequestMapping("/fd")
public class FdDomainController {

    private static final Logger log = LoggerFactory.getLogger(FdDomainController.class);

    private final FdDomainService fdDomainService;

    public FdDomainController(FdDomainService fdDomainService) {
        this.fdDomainService = fdDomainService;
    }

    // ── POST /internal/fd/validate ────────────────────────────────────────

    /**
     * Run all FD validations: KYC → source account → scheme eligibility.
     * Called by fd-validate Job Worker.
     * Request body: full FD payload fetched from S3 by the job worker.
     *
     * 200 OK                → validation passed
     * 422 Unprocessable     → validation failed (reason in body)
     * 500 Internal          → unexpected error (job worker will retry)
     */
    @PostMapping("/validate")
    public Mono<ResponseEntity<ValidateResult>> validate(
            @RequestBody Map<String, Object> payload) {

        String applicationId = (String) payload.get("applicationId");
        log.info("Validate request received applicationId={}", applicationId);

        return fdDomainService.validate(applicationId, payload)
            .map(result -> {
                if (!result.valid()) {
                    log.warn("Validation failed applicationId={} reason={}", applicationId, result.reason());
                    return ResponseEntity
                        .status(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body(result);
                }
                log.info("Validation passed applicationId={}", applicationId);
                return ResponseEntity.ok(result);
            })
            .onErrorResume(e -> {
                log.error("Validate endpoint error applicationId={}", applicationId, e);
                return Mono.just(ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .<ValidateResult>body(new ValidateResult(false, "Internal error: " + e.getMessage(), Map.of())));
            });
    }

    // ── POST /internal/fd/{applicationId}/draft ───────────────────────────

    /**
     * Transition DBP application state to DRAFT.
     * Called by fd-persist-draft Job Worker (SELF_SERVICE journeys only —
     * the BPMN gateway ensures ASSISTED journeys never reach this task).
     *
     * 200 OK            → state updated to DRAFT
     * 500 Internal      → update failed (job worker will retry)
     */
    @PostMapping("/{applicationId}/draft")
    public Mono<ResponseEntity<DraftResult>> persistDraft(
            @PathVariable String applicationId) {

        log.info("Persist draft request applicationId={}", applicationId);

        return fdDomainService.persistDraft(applicationId)
            .map(ResponseEntity::ok)
            .onErrorResume(e -> {
                log.error("Persist draft failed applicationId={}", applicationId, e);
                return Mono.just(ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .<DraftResult>build());
            });
    }

    // ── POST /internal/fd/cbs-open ────────────────────────────────────────

    /**
     * Open FD in CBS. Final step for both SELF_SERVICE and ASSISTED journeys.
     * Called by fd-cbs-open Job Worker.
     * Request body: full FD payload fetched from S3 by the job worker.
     *
     * 200 OK            → FD opened in CBS; body contains fdAccountNumber
     * 502 Bad Gateway   → CBS rejected or unavailable (job worker throws BPMN error)
     * 500 Internal      → unexpected error (job worker will retry)
     */
    @PostMapping("/cbs-open")
    public Mono<ResponseEntity<CbsOpenResult>> openInCbs(
            @RequestBody Map<String, Object> payload) {

        String applicationId = (String) payload.get("applicationId");
        log.info("CBS open request applicationId={}", applicationId);

        return fdDomainService.openInCbs(applicationId, payload)
            .map(result -> {
                log.info("CBS open succeeded applicationId={} fdAccountNo={}",
                    applicationId, result.fdAccountNumber());
                return ResponseEntity.ok(result);
            })
            .onErrorResume(e -> {
                log.error("CBS open failed applicationId={}", applicationId, e);
                // Return 502 so the job worker can distinguish CBS failure
                // from internal error and throw the correct BPMN error code
                return Mono.just(ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .<CbsOpenResult>build());
            });
    }

    // ── GET /internal/fd/{applicationId} ─────────────────────────────────

    /**
     * Fetch FD application state by applicationId.
     * Used by job workers or support tooling to inspect current DBP state.
     *
     * 200 OK        → FdApplication record
     * 404 Not Found → no record for this applicationId
     */
    @GetMapping("/{applicationId}")
    public Mono<ResponseEntity<FdApplication>> getApplication(
            @PathVariable String applicationId) {

        log.debug("Get application request applicationId={}", applicationId);

        return fdDomainService.getApplicationById(applicationId)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build())
            .onErrorResume(e -> {
                log.error("Get application failed applicationId={}", applicationId, e);
                return Mono.just(ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .<FdApplication>build());
            });
    }
}
