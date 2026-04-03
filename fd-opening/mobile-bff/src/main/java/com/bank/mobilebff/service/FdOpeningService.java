package com.bank.mobilebff.service;

import com.bank.mobilebff.model.FdOpenRequest;
import com.bank.mobilebff.model.ZeebeProcessVariables;
import com.bank.mobilebff.zeebe.ZeebeClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Orchestrates the Mobile BFF FD opening flow:
 *
 *  1. Enrich request (applicationId, timestamp)
 *  2. Check duplicate active journey in Zeebe
 *  3. Persist full payload to S3
 *  4. Create Zeebe process instance with minimal variables
 *  5. Return instanceId + applicationId to controller
 */
@Service
public class FdOpeningService {

    private static final Logger log = LoggerFactory.getLogger(FdOpeningService.class);

    private final S3PayloadService  s3PayloadService;
    private final ZeebeClientService zeebeClientService;

    public FdOpeningService(S3PayloadService s3PayloadService,
                             ZeebeClientService zeebeClientService) {
        this.s3PayloadService  = s3PayloadService;
        this.zeebeClientService = zeebeClientService;
    }

    public record FdInitiateResult(String applicationId, long processInstanceKey) {}

    /**
     * Initiate FD journey: persist payload → start Zeebe process.
     */
    public Mono<FdInitiateResult> initiateFdJourney(FdOpenRequest rawRequest) {
        // Step 1: Enrich with applicationId and timestamp
        FdOpenRequest enriched = new FdOpenRequest(
            rawRequest.customerId(),
            rawRequest.sourceAccountNo(),
            rawRequest.channelId(),
            rawRequest.schemeCode(),
            rawRequest.tenureMonths(),
            rawRequest.principalAmount(),
            rawRequest.currency(),
            rawRequest.maturityInstruction(),
            rawRequest.nomineeDetails(),
            UUID.randomUUID().toString(),   // applicationId
            "SELF_SERVICE",                 // Mobile is always self-service
            Instant.now()
        );

        String redacted = ZeebeProcessVariables.redact(enriched.customerId());

        // Step 2: Duplicate journey guard
        return zeebeClientService.hasActiveJourney(redacted, "SELF_SERVICE")
            .flatMap(active -> {
                if (active) {
                    return Mono.error(new DuplicateJourneyException(
                        "Active FD journey already exists for customer"));
                }

                // Step 3: Persist full payload to S3
                return s3PayloadService.persistPayload(enriched)
                    .flatMap(s3Key -> {
                        log.info("Payload stored s3Key={}", s3Key);

                        // Step 4: Create Zeebe process with minimal vars
                        ZeebeProcessVariables vars = new ZeebeProcessVariables(
                            enriched.applicationId(),
                            enriched.journeyType(),
                            redacted,
                            enriched.channelId(),
                            "INITIATED",
                            enriched.requestTimestamp()
                        );

                        return zeebeClientService.createFdProcess(vars)
                            .map(instanceKey ->
                                new FdInitiateResult(enriched.applicationId(), instanceKey));
                    });
            })
            .doOnSuccess(r -> log.info("FD journey initiated applicationId={} instanceKey={}",
                r.applicationId(), r.processInstanceKey()))
            .doOnError(e -> log.error("FD journey initiation failed", e));
    }

    /**
     * Customer confirms on review page – correlate submit message to Zeebe.
     */
    public Mono<Void> submitFdFromReview(String applicationId) {
        log.info("Customer submit from review page applicationId={}", applicationId);
        return zeebeClientService.correlateCustomerSubmit(applicationId);
    }

    public static class DuplicateJourneyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

		public DuplicateJourneyException(String message) { super(message); }
    }
}
