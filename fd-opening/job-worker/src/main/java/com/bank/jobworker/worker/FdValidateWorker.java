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
 * Zeebe Job Worker: fd-validate
 *
 * Responsibilities:
 *   1. Extract applicationId from Zeebe job variables
 *   2. Fetch full payload from S3
 *   3. Call FD Domain Service for validation
 *   4. Complete job with validation result variables
 *   5. Throw BPMN error on validation failure → Zeebe boundary event handles it
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

    @JobWorker(type = "fd-validate", autoComplete = false)
    public void handleFdValidate(JobClient jobClient, ActivatedJob job) {
        String applicationId = (String) job.getVariablesAsMap().get("applicationId");
        String journeyType   = (String) job.getVariablesAsMap().get("journeyType");

        log.info("FdValidateWorker started applicationId={} journeyType={}", applicationId, journeyType);

        try {
            // 1. Fetch payload from S3 (full request)
            Map<String, Object> payload = s3PayloadFetcher.fetchPayload(applicationId);

            // 2. Call Domain Service for validations
            FdDomainClient.ValidationResult result =
                fdDomainClient.validateFd(applicationId, payload);

            if (!result.valid()) {
                // Throw BPMN error → boundary event catches it in the process
                log.warn("FD validation failed applicationId={} reason={}", applicationId, result.reason());
                jobClient.newThrowErrorCommand(job.getKey())
                    .errorCode(ERROR_CODE)
                    .errorMessage(result.reason())
                    .send()
                    .join();
                return;
            }

            // 3. Complete job – pass validation outcome as Zeebe variables
            log.info("FD validation passed applicationId={}", applicationId);
            jobClient.newCompleteCommand(job.getKey())
                .variables(Map.of(
                    "validationStatus",  "PASSED",
                    "validationDetails", result.details() != null ? result.details() : Map.of()
                ))
                .send()
                .join();

        } catch (Exception e) {
            log.error("FdValidateWorker unexpected error applicationId={}", applicationId, e);
            // Fail job with retries – Zeebe will retry based on task definition retries=3
            jobClient.newFailCommand(job.getKey())
                .retries(job.getRetries() - 1)
                .errorMessage("Unexpected error: " + e.getMessage())
                .send()
                .join();
        }
    }
}
