package com.bank.mobilebff.controller;

import com.bank.mobilebff.model.FdOpenRequest;
import com.bank.mobilebff.service.FdOpeningService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Mobile BFF – FD Opening endpoints.
 *
 *  POST  /api/v1/fd/initiate        – start FD journey (validate + persist + Zeebe)
 *  POST  /api/v1/fd/{appId}/submit  – customer confirms from review screen
 *  GET   /api/v1/fd/{appId}/status  – poll journey status (delegates to Zeebe)
 */
@RestController
@RequestMapping("/api/v1/fd")
public class FdOpeningController {

    private static final Logger log = LoggerFactory.getLogger(FdOpeningController.class);

    private final FdOpeningService fdOpeningService;

    public FdOpeningController(FdOpeningService fdOpeningService) {
        this.fdOpeningService = fdOpeningService;
    }

    /**
     * Initiate FD journey.
     * 201 Created → { applicationId, processInstanceKey }
     */
    @PostMapping("/initiate")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ResponseEntity<Map<String, Object>>> initiateFd(
            @Valid @RequestBody FdOpenRequest request) {

        log.info("FD initiate request received customerId={}", request.customerId());

        return fdOpeningService.initiateFdJourney(request)
            .map(result -> ResponseEntity
                .status(HttpStatus.CREATED)
                .body(Map.of(
                    "applicationId",      result.applicationId(),
                    "processInstanceKey", result.processInstanceKey(),
                    "status",             "INITIATED",
                    "message",            "FD journey initiated. Proceed to review."
                )))
            .onErrorResume(FdOpeningService.DuplicateJourneyException.class, e ->
                Mono.just(ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(Map.of(
                        "error",   "DUPLICATE_JOURNEY",
                        "message", e.getMessage()
                    )))
            )
            .onErrorResume(e -> {
                log.error("Unexpected error during FD initiation", e);
                return Mono.just(ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "INTERNAL_ERROR", "message", "Please retry")));
            });
    }

    /**
     * Customer confirms FD from review page.
     * Correlates the submit message in Zeebe to proceed to CBS open.
     * 200 OK → { status: SUBMITTED }
     */
    @PostMapping("/{applicationId}/submit")
    public Mono<ResponseEntity<Map<String, Object>>> submitFd(
            @PathVariable String applicationId) {

        log.info("Customer FD submit from review applicationId={}", applicationId);

        return fdOpeningService.submitFdFromReview(applicationId)
            .thenReturn(ResponseEntity.ok(Map.<String, Object>of(
                "applicationId", applicationId,
                "status",        "SUBMITTED",
                "message",       "FD request submitted for opening in CBS."
            )))
            .onErrorResume(e -> {
                log.error("Submit failed for applicationId={}", applicationId, e);
                return Mono.just(ResponseEntity
                    .status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", "SUBMIT_FAILED", "message", e.getMessage())));
            });
    }

    /**
     * Poll FD journey status.
     * Clients can poll using the processInstanceKey returned at initiation.
     */
    @GetMapping("/{applicationId}/status")
    public Mono<ResponseEntity<Map<String, Object>>> getStatus(
            @PathVariable String applicationId) {
        // Delegate to Zeebe search or a local status cache; simplified here
        return Mono.just(ResponseEntity.ok(Map.of(
            "applicationId", applicationId,
            "message",       "Query Zeebe using processInstanceKey for live status"
        )));
    }
}
