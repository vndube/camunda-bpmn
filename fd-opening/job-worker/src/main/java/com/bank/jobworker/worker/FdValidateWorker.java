package com.bank.jobworker.worker;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.bank.jobworker.service.FdDomainClient;
import com.bank.jobworker.service.S3PayloadFetcher;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.response.CompleteJobResponse;
import io.camunda.zeebe.client.api.response.FailJobResponse;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import reactor.core.publisher.Mono;

/**
 * Zeebe Job Worker: fd-validate
 *
 * Responsibilities: Fetch S3 -> Call Domain -> Complete or Throw BPMN Error (Reactive).
 */
@Component
public class FdValidateWorker {

    private static final Logger log = LoggerFactory.getLogger(FdValidateWorker.class);
    private static final String ERROR_CODE = "FD_VALIDATION_FAILED";

    private final S3PayloadFetcher s3PayloadFetcher;
    private final FdDomainClient   fdDomainClient;

    public FdValidateWorker(S3PayloadFetcher s3PayloadFetcher, FdDomainClient fdDomainClient) {
        this.s3PayloadFetcher = s3PayloadFetcher;
        this.fdDomainClient   = fdDomainClient;
    }

    @JobWorker(type = "fd-validate")
    public Mono<Void> handleFdValidate(JobClient jobClient, ActivatedJob job) {
        String applicationId = (String) job.getVariablesAsMap().get("applicationId");
        String journeyType   = (String) job.getVariablesAsMap().get("journeyType");

        log.info("FdValidateWorker started applicationId={} journeyType={}", applicationId, journeyType);

        // 1. Fetch payload from S3 (Reactive)
        return s3PayloadFetcher.fetchPayload(applicationId)
            // 2. Chain Domain Validation Call (Reactive)
            .flatMap(payload -> fdDomainClient.validateFd(applicationId, payload))
            // 3. Coordinate Zeebe Response based on Result (Reactive)
            .flatMap(result -> {
                if (!result.valid()) {
                    // Business Logic Violation -> Throw BPMN error reactively
                    log.warn("FD validation failed applicationId={} reason={}", applicationId, result.reason());
						CompletableFuture<Void> standardFuture = 
								jobClient.newThrowErrorCommand(job.getKey())
								.errorCode(ERROR_CODE)
								.errorMessage(result.reason())
								.send()
								.toCompletableFuture();
                    
                    return Mono.fromFuture(standardFuture).then();
                }

                // Success -> Complete job reactively with variables
                log.info("FD validation passed applicationId={}", applicationId);
                
                CompletableFuture<CompleteJobResponse> zeebeFuture =
                jobClient.newCompleteCommand(job.getKey())
                .variables(Map.of(
                    "validationStatus",  "PASSED",
                    "validationDetails", result.details() != null ? result.details() : Map.of()
                ))
                .send().toCompletableFuture();
                return Mono.fromFuture(zeebeFuture).then();
            })
            // 4. Handle Unexpected Technical Error
            .onErrorResume(e -> {
                log.error("FdValidateWorker unexpected error applicationId={}", applicationId, e);
                
                CompletableFuture<FailJobResponse> failedFuture =
                jobClient.newFailCommand(job.getKey())
                .retries(job.getRetries() - 1)
                .errorMessage("Unexpected error: " + e.getMessage())
                .send().toCompletableFuture();
                return Mono.fromFuture(failedFuture).then();
            });
//            // 5. Transform final flow to Mono<Void>
//            .then();
    }
}