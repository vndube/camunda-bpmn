package com.bank.mobilebff.zeebe;

import com.bank.mobilebff.model.ZeebeProcessVariables;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ProcessInstanceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * Reactive wrapper around the Zeebe Java Client 8.5.x.
 *
 * Verified API surface for zeebe-client-java:8.5.x:
 *   - ZeebeClient.newCreateInstanceCommand()   → start process
 *   - ZeebeClient.newPublishMessageCommand()   → correlate message
 *
 * NOTE: ZeebeClient 8.5 does NOT expose a process-instance search API.
 * Active journey detection is delegated to Camunda Operate REST API
 * (available alongside every Camunda 8 self-managed deployment).
 * Operate endpoint: POST /v1/process-instances/search
 *
 * All blocking Zeebe SDK calls are wrapped in Mono.fromCallable(...)
 * .subscribeOn(Schedulers.boundedElastic()) to avoid blocking the
 * Spring WebFlux event loop.
 */
@Service
public class ZeebeClientService {

    private static final Logger log = LoggerFactory.getLogger(ZeebeClientService.class);

    private static final String PROCESS_ID     = "fd-opening-process";
    private static final String SUBMIT_MESSAGE = "customer-fd-submit";

    private final ZeebeClient zeebeClient;
    private final WebClient   operateWebClient;

    public ZeebeClientService(
            ZeebeClient zeebeClient,
            WebClient.Builder webClientBuilder,
            @Value("${camunda.operate.base-url:http://localhost:8081}") String operateBaseUrl,
            @Value("${camunda.operate.username:demo}") String operateUsername,
            @Value("${camunda.operate.password:demo}") String operatePassword) {

        this.zeebeClient = zeebeClient;

        // Operate REST client — basic auth for self-managed dev/test.
        // Switch to OAuth2 bearer token for production environments.
        this.operateWebClient = webClientBuilder
            .baseUrl(operateBaseUrl)
            .defaultHeaders(h -> {
                h.setContentType(MediaType.APPLICATION_JSON);
                h.setBasicAuth(operateUsername, operatePassword);
            })
            .build();
    }

    // ── 1. Create process instance ────────────────────────────────────────

    /**
     * Start a new FD process instance in Zeebe.
     * Only minimal, non-PII variables are passed; full payload lives in S3.
     *
     * API: ZeebeClient.newCreateInstanceCommand() — stable since Zeebe 1.x
     *
     * @return Mono of processInstanceKey — return this to the caller as instanceId
     */
    public Mono<Long> createFdProcess(ZeebeProcessVariables vars) {
        Map<String, Object> variables = Map.of(
            "applicationId",      vars.applicationId(),
            "journeyType",        vars.journeyType(),
            "redactedCustomerId", vars.redactedCustomerId(),
            "channelId",          vars.channelId(),
            "status",             vars.status(),
            "initiatedAt",        vars.initiatedAt().toString()
        );

        return Mono.fromCallable(() -> {
                ProcessInstanceEvent event = zeebeClient
                    .newCreateInstanceCommand()
                    .bpmnProcessId(PROCESS_ID)
                    .latestVersion()
                    .variables(variables)
                    .send()
                    .join();   // blocking — safe on boundedElastic scheduler

                log.info("Zeebe process created processInstanceKey={} applicationId={}",
                    event.getProcessInstanceKey(), vars.applicationId());

                return event.getProcessInstanceKey();
            })
            .subscribeOn(Schedulers.boundedElastic())
            .doOnError(e -> log.error(
                "Zeebe createProcess failed applicationId={}", vars.applicationId(), e));
    }

    // ── 2. Correlate customer-submit message ──────────────────────────────

    /**
     * Publishes "customer-fd-submit" message to Zeebe.
     * Correlates with the IntermediateCatchEvent using applicationId as the key.
     *
     * API: ZeebeClient.newPublishMessageCommand() — stable since Zeebe 1.x
     *
     * messageId = applicationId ensures exactly-once delivery:
     * Zeebe deduplicates messages with identical (messageName + messageId)
     * within the message TTL window, preventing double-correlations on retries.
     */
    public Mono<Void> correlateCustomerSubmit(String applicationId) {
        return Mono.fromRunnable(() ->
                zeebeClient
                    .newPublishMessageCommand()
                    .messageName(SUBMIT_MESSAGE)
                    .correlationKey(applicationId)   // matches BPMN catch event correlation key
                    .messageId(applicationId)         // idempotency guard against duplicate submits
                    .variables(Map.of("status", "SUBMITTED"))
                    .send()
                    .join()
            )
            .subscribeOn(Schedulers.boundedElastic())
            .then()
            .doOnSuccess(v -> log.info(
                "Message published messageName={} correlationKey={}", SUBMIT_MESSAGE, applicationId))
            .doOnError(e -> log.error(
                "Zeebe message publish failed applicationId={}", applicationId, e));
    }

    // ── 3. Active journey check via Camunda Operate REST API ──────────────

    /**
     * Checks whether an ACTIVE FD process instance already exists for this
     * customer + journeyType, preventing duplicate in-flight journeys.
     *
     * Zeebe 8.5 Java client has NO built-in search API.
     * We delegate to the Camunda Operate REST API:
     *   POST {operate-base-url}/v1/process-instances/search
     *
     * Operate is part of every Camunda 8 self-managed distribution.
     * Docs: https://docs.camunda.io/docs/apis-tools/operate-api/overview/
     *
     * Fails open on Operate outage — customer journey proceeds.
     */
    public Mono<Boolean> hasActiveJourney(String redactedCustomerId, String journeyType) {
        // Operate search filter: process + state=ACTIVE + variable match
        Map<String, Object> searchBody = Map.of(
            "filter", Map.of(
                "bpmnProcessId", PROCESS_ID,
                "state",         "ACTIVE"
            ),
            "variables", List.of(
                Map.of("name", "redactedCustomerId",
                       "value", "\"" + redactedCustomerId + "\""),
                Map.of("name", "journeyType",
                       "value", "\"" + journeyType + "\"")
            ),
            "size", 1
        );

        return operateWebClient
            .post()
            .uri("/v1/process-instances/search")
            .bodyValue(searchBody)
            .retrieve()
            .bodyToMono(OperateSearchResponse.class)
            .map(resp -> resp.total() > 0)
            .doOnSuccess(active -> log.debug(
                "Active journey check redactedCustomerId={} journeyType={} active={}",
                redactedCustomerId, journeyType, active))
            .onErrorResume(e -> {
                // Fail-open: a temporary Operate outage should not block customers
                log.warn("Operate search unavailable — failing open. error={}", e.getMessage());
                return Mono.just(false);
            });
    }

    // ── 4. Process instance status via Operate REST API ───────────────────

    /**
     * Returns the current state of a process instance by its key.
     * Called by the BFF status endpoint — clients poll with the
     * processInstanceKey returned at journey initiation.
     *
     * Operate REST API: GET /v1/process-instances/{key}
     * States: ACTIVE | COMPLETED | CANCELED | INCIDENT
     */
    public Mono<ProcessInstanceStatus> getProcessInstanceStatus(long processInstanceKey) {
        return operateWebClient
            .get()
            .uri("/v1/process-instances/{key}", processInstanceKey)
            .retrieve()
            .bodyToMono(OperateInstanceResponse.class)
            .map(resp -> new ProcessInstanceStatus(
                processInstanceKey,
                resp.state(),
                resp.endDate() != null
            ))
            .doOnError(e -> log.error(
                "Operate fetch failed processInstanceKey={}", processInstanceKey, e));
    }

    // ── Internal response records (Operate REST API shapes) ───────────────

    /** POST /v1/process-instances/search — top-level response */
    private record OperateSearchResponse(long total, List<Object> items) {}

    /** GET /v1/process-instances/{key} — response */
    private record OperateInstanceResponse(
        long   key,
        String state,      // ACTIVE | COMPLETED | CANCELED | INCIDENT
        String startDate,
        String endDate     // null while ACTIVE
    ) {}

    // ── Public result type ────────────────────────────────────────────────

    public record ProcessInstanceStatus(
        long    processInstanceKey,
        String  state,
        boolean ended
    ) {}
}
