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
 * Zeebe Job Worker: fd-cbs-open
 *
 * Final step in the FD journey.
 * Fetches payload from S3, sends to Domain Service → CBS.
 * On success, updates Zeebe variables reactively.
 */
@Component
public class FdCbsOpenWorker {

    private static final Logger log = LoggerFactory.getLogger(FdCbsOpenWorker.class);
    private static final String ERROR_CODE = "FD_CBS_FAILURE";

    private final S3PayloadFetcher s3PayloadFetcher;
    private final FdDomainClient   fdDomainClient;

    public FdCbsOpenWorker(S3PayloadFetcher s3PayloadFetcher, FdDomainClient fdDomainClient) {
        this.s3PayloadFetcher = s3PayloadFetcher;
        this.fdDomainClient   = fdDomainClient;
    }

    /**
     * autoComplete = true (default) allows the SDK to complete the job
     * when the returned Mono<Void> completes successfully.
     */
    @JobWorker(type = "fd-cbs-open")
    public Mono<Void> handleCbsOpen(JobClient jobClient, ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String applicationId     = (String) vars.get("applicationId");
        String journeyType       = (String) vars.get("journeyType");

        log.info("FdCbsOpenWorker started applicationId={} journeyType={}", applicationId, journeyType);


        
        // 1. Fetch payload from S3 (Reactive)
        return s3PayloadFetcher.fetchPayload(applicationId)
            // 2. Chain the Domain Service call (Reactive)
            .flatMap(payload -> fdDomainClient.openInCbs(applicationId, payload))
            // 3. Chain the Zeebe Completion Command (Reactive)
            .flatMap(result -> {
                log.info("FD opened in CBS applicationId={} fdAccountNo={} cbsTxnId={}",
                    applicationId, result.fdAccountNumber(), result.cbsTransactionId());
               
                 CompletableFuture<CompleteJobResponse> zeebeFuture = jobClient.newCompleteCommand(job.getKey())
                        .variables(Map.of(
                                "status",           "COMPLETE",
                                "fdAccountNumber",  result.fdAccountNumber(),
                                "cbsTransactionId", result.cbsTransactionId()
                            )).send().toCompletableFuture(); 
                
                return Mono.fromFuture(zeebeFuture).then();
            })
            // 4. Handle Errors Reactively (Replaces try-catch)
            .onErrorResume(FdCbsException.class, e -> {
                log.error("CBS open rejected applicationId={} reason={}", applicationId, e.getMessage());

             // ── RECOMMENDED APPROACH FOR ZEEBE THROW ERROR ──
                // This lambda is ONLY executed when the previous Mono (Domain Call) completes.
                // We create the 'ZeebeFuture' on-demand inside the lambda.
                
                io.camunda.zeebe.client.api.ZeebeFuture<Void> zeebeFuture = 
                    jobClient.newThrowErrorCommand(job.getKey())
                        .errorCode(ERROR_CODE)
                        .errorMessage(e.getMessage())
                        .send(); // 1. Trigger the gRPC Call

                // 2. Conver the *ZeebeFuture* into a standard *CompletableFuture*.
                // This is the CRITICAL missing step from your code.
                java.util.concurrent.CompletableFuture<Void> standardFuture = 
                    zeebeFuture.toCompletableFuture();

                // 3. Transform the standard Future into a cold Mono and return it.
                // Reactor can now track the completion/failure of the 'ThrowError' command.
                return Mono.fromFuture(standardFuture);
            })
            .onErrorResume(e -> {
                // Technical/Unexpected Failure
            	CompletableFuture<FailJobResponse> standardFuture = 
            	jobClient.newFailCommand(job.getKey())
                .retries(job.getRetries() - 1)
                .errorMessage("Failed due to Technical error: " + e.getMessage())
                .send().toCompletableFuture();
            	
                log.error("FdCbsOpenWorker unexpected error applicationId={}", applicationId, e);
                return Mono.fromFuture(standardFuture).then();
            });
//            // 5. Transform final result to Mono<Void>
//            .then();
    }

    public static class FdCbsException extends RuntimeException {
        private static final long serialVersionUID = 1L;
		public FdCbsException(String message) { super(message); }
    }
}