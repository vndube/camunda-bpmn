package com.bank.jobworker.worker;

import com.bank.jobworker.service.FdDomainClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.response.CompleteJobResponse;
import io.camunda.zeebe.client.api.response.FailJobResponse;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Zeebe Job Worker: fd-persist-draft
 *
 * Instructs Domain Service to transition DBP application state → DRAFT reactively.
 */
@Component
public class FdPersistDraftWorker {

    private static final Logger log = LoggerFactory.getLogger(FdPersistDraftWorker.class);

    private final FdDomainClient fdDomainClient;

    public FdPersistDraftWorker(FdDomainClient fdDomainClient) {
        this.fdDomainClient = fdDomainClient;
    }

    @JobWorker(type = "fd-persist-draft")
    public Mono<Void> handlePersistDraft(JobClient jobClient, ActivatedJob job) {
        Map<String, Object> vars  = job.getVariablesAsMap();
        String applicationId      = (String) vars.get("applicationId");

        log.info("FdPersistDraftWorker started applicationId={}", applicationId);

        // 1. Invoke Domain Service (Reactive)
        return fdDomainClient.persistDraft(applicationId)
            // 2. Chain Job Completion (Reactive)
            .flatMap(result -> {
                log.info("FD draft persisted applicationId={} status={}", applicationId, result.status());

                
                CompletableFuture<CompleteJobResponse> zeebeFuture = 
                		jobClient.newCompleteCommand(job.getKey())
                .variables(Map.of("status", "DRAFT"))
                .send().toCompletableFuture();
                
                return Mono.fromFuture(zeebeFuture).then();
            })
            // 3. Handle Unexpected Technical Error
            .onErrorResume(e -> {
                log.error("FdPersistDraftWorker error applicationId={}", applicationId, e);
                
                // Technical/Unexpected Failure
					CompletableFuture<FailJobResponse> standardFuture 
					    = jobClient.newFailCommand(job.getKey())
							.retries(job.getRetries() - 1)
							.errorMessage("Draft persist failed: " + e.getMessage())
							.send().toCompletableFuture();
            	
                return Mono.fromFuture(standardFuture).then();

            });
//            // 4. Transform to Mono<Void>
//            .then();
    }
}