package com.bank.mobilebff.zeebe;

import com.bank.mobilebff.model.ZeebeProcessVariables;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ProcessInstanceEvent;
import io.camunda.zeebe.client.api.response.ProcessInstanceResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * Thin reactive wrapper around the Zeebe Java client.
 *
 * BFF responsibilities:
 *   1. createFdProcess()       – start a new process instance
 *   2. correlateCustomerSubmit() – send message when customer confirms on review page
 *   3. searchActiveJourney()   – check if an active journey already exists
 */
@Service
public class ZeebeClientService {

    private static final Logger log = LoggerFactory.getLogger(ZeebeClientService.class);
    private static final String PROCESS_ID         = "fd-opening-process";
    private static final String SUBMIT_MESSAGE     = "customer-fd-submit";

    private final ZeebeClient zeebeClient;

    public ZeebeClientService(ZeebeClient zeebeClient) {
        this.zeebeClient = zeebeClient;
    }

    /**
     * Creates a new FD process instance in Zeebe.
     * Only minimal variables are passed – no PII payload.
     *
     * @return Mono of process instanceKey (use as instanceId in BFF response)
     */
    public Mono<Long> createFdProcess(ZeebeProcessVariables vars) {
        Map<String, Object> variables = Map.of(
            "applicationId",       vars.applicationId(),
            "journeyType",         vars.journeyType(),
            "redactedCustomerId",  vars.redactedCustomerId(),
            "channelId",           vars.channelId(),
            "status",              vars.status(),
            "initiatedAt",         vars.initiatedAt().toString()
        );

        return Mono.fromCallable(() -> {
                ProcessInstanceEvent event = zeebeClient
                    .newCreateInstanceCommand()
                    .bpmnProcessId(PROCESS_ID)
                    .latestVersion()
                    .variables(variables)
                    .send()
                    .join();
                log.info("Zeebe process created processInstanceKey={} applicationId={}",
                    event.getProcessInstanceKey(), vars.applicationId());
                return event.getProcessInstanceKey();
            })
            .subscribeOn(Schedulers.boundedElastic())
            .doOnError(e -> log.error("Zeebe createProcess failed applicationId={}", vars.applicationId(), e));
    }

    /**
     * Correlates customer-review-submit message.
     * Called when customer taps "Confirm & Open FD" on Mobile review page.
     *
     * @param applicationId used as the correlation key
     */
    public Mono<Void> correlateCustomerSubmit(String applicationId) {
        return Mono.fromRunnable(() -> {
                zeebeClient
                    .newPublishMessageCommand()
                    .messageName(SUBMIT_MESSAGE)
                    .correlationKey(applicationId)
                    .variables(Map.of("status", "SUBMITTED"))
                    .send()
                    .join();
                log.info("Message correlated messageName={} correlationKey={}", SUBMIT_MESSAGE, applicationId);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .then()
            .doOnError(e -> log.error("Zeebe correlate failed applicationId={}", applicationId, e));
    }

    /**
     * Search for an active process instance by journeyType + redactedCustomerId.
     * Uses Zeebe Operate / Tasklist search API (REST) via webclient.
     *
     * For embedded search: filter on process variables using the Zeebe gRPC
     * SearchProcessInstancesRequest (Zeebe 8.3+).
     */
    public Mono<Boolean> hasActiveJourney(String redactedCustomerId, String journeyType) {
        return Mono.fromCallable(() -> {
                // Zeebe 8.3+ variable-based search
                var result = zeebeClient
                    .newProcessInstanceSearchRequest()
                    .filter(f -> f
                        .bpmnProcessId(PROCESS_ID)
                        .variable("redactedCustomerId", redactedCustomerId)
                        .variable("journeyType", journeyType)
                        .running(true)             // active only
                    )
                    .send()
                    .join();
                boolean active = !result.items().isEmpty();
                log.debug("Active journey check customerId={} journeyType={} result={}",
                    redactedCustomerId, journeyType, active);
                return active;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorReturn(false);   // fail-open: let BFF proceed if search errors
    }
}
