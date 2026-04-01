package com.bank.jobworker.worker;

import com.bank.jobworker.service.FdDomainClient;
import com.bank.jobworker.service.S3PayloadFetcher;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Zeebe Job Worker: fd-cbs-open
 *
 * Final step in the FD journey for both SELF_SERVICE and ASSISTED.
 * Fetches payload from S3, sends to Domain Service → CBS Adapter → CBS.
 * On success, updates Zeebe variables with FD account number and COMPLETE status.
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

    @JobWorker(type = "fd-cbs-open", autoComplete = false)
    public void handleCbsOpen(JobClient jobClient, ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String applicationId     = (String) vars.get("applicationId");
        String journeyType       = (String) vars.get("journeyType");

        log.info("FdCbsOpenWorker started applicationId={} journeyType={}", applicationId, journeyType);

        try {
            // 1. Fetch full payload from S3
            Map<String, Object> payload = s3PayloadFetcher.fetchPayload(applicationId);

            // 2. Invoke Domain Service → CBS Adapter
            FdDomainClient.CbsOpenResult result = fdDomainClient.openInCbs(applicationId, payload);

            log.info("FD opened in CBS applicationId={} fdAccountNo={} cbsTxnId={}",
                applicationId, result.fdAccountNumber(), result.cbsTransactionId());

            // 3. Complete job with minimal outcome vars
            jobClient.newCompleteCommand(job.getKey())
                .variables(Map.of(
                    "status",           "COMPLETE",
                    "fdAccountNumber",  result.fdAccountNumber(),
                    "cbsTransactionId", result.cbsTransactionId()
                ))
                .send()
                .join();

        } catch (FdCbsException e) {
            log.error("CBS open rejected applicationId={} reason={}", applicationId, e.getMessage());
            jobClient.newThrowErrorCommand(job.getKey())
                .errorCode(ERROR_CODE)
                .errorMessage(e.getMessage())
                .send()
                .join();

        } catch (Exception e) {
            log.error("FdCbsOpenWorker unexpected error applicationId={}", applicationId, e);
            jobClient.newFailCommand(job.getKey())
                .retries(job.getRetries() - 1)
                .errorMessage("CBS open failed: " + e.getMessage())
                .send()
                .join();
        }
    }

    public static class FdCbsException extends RuntimeException {
        public FdCbsException(String message) { super(message); }
    }
}
