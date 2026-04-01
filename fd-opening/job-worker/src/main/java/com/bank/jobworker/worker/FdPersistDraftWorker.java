package com.bank.jobworker.worker;

import com.bank.jobworker.service.FdDomainClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Zeebe Job Worker: fd-persist-draft
 *
 * Only activated for SELF_SERVICE journey (BPMN gateway guards this).
 * Instructs Domain Service to transition DBP application state → DRAFT.
 * The full payload is already in S3; domain service reads it from there.
 */
@Component
public class FdPersistDraftWorker {

    private static final Logger log = LoggerFactory.getLogger(FdPersistDraftWorker.class);

    private final FdDomainClient fdDomainClient;

    public FdPersistDraftWorker(FdDomainClient fdDomainClient) {
        this.fdDomainClient = fdDomainClient;
    }

    @JobWorker(type = "fd-persist-draft", autoComplete = false)
    public void handlePersistDraft(JobClient jobClient, ActivatedJob job) {
        Map<String, Object> vars  = job.getVariablesAsMap();
        String applicationId      = (String) vars.get("applicationId");

        log.info("FdPersistDraftWorker started applicationId={}", applicationId);

        try {
            FdDomainClient.PersistDraftResult result = fdDomainClient.persistDraft(applicationId);

            log.info("FD draft persisted applicationId={} status={}", applicationId, result.status());

            jobClient.newCompleteCommand(job.getKey())
                .variables(Map.of("status", "DRAFT"))
                .send()
                .join();

        } catch (Exception e) {
            log.error("FdPersistDraftWorker error applicationId={}", applicationId, e);
            jobClient.newFailCommand(job.getKey())
                .retries(job.getRetries() - 1)
                .errorMessage("Draft persist failed: " + e.getMessage())
                .send()
                .join();
        }
    }
}
