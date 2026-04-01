package com.bank.fdservice.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * Adapter that translates the DBP FD request into a CBS-compatible payload
 * and submits it to the Core Banking System.
 *
 * CBS contract (example – adapt to your CBS API):
 *
 *  POST /cbs/api/v1/term-deposits
 *  {
 *    "channelId":           "DBP",
 *    "customerId":          "...",
 *    "sourceAccountNo":     "...",
 *    "schemeCode":          "...",
 *    "tenureMonths":        12,
 *    "principalAmount":     50000.00,
 *    "currency":            "INR",
 *    "maturityInstruction": "AUTO_RENEW"
 *  }
 *
 *  Response:
 *  {
 *    "tdAccountNumber":   "TD00012345",
 *    "transactionId":     "CBS-TXN-98765",
 *    "status":            "SUCCESS"
 *  }
 */
@Component
public class CbsAdapter {

    private static final Logger log = LoggerFactory.getLogger(CbsAdapter.class);

    private final WebClient  webClient;
    private final Duration   timeout;
    private final String     channelId;

    public CbsAdapter(
            WebClient.Builder builder,
            @Value("${cbs.base-url}") String cbsBaseUrl,
            @Value("${cbs.timeout-seconds:25}") int timeoutSec,
            @Value("${cbs.channel-id:DBP}") String channelId) {
        this.webClient = builder
            .baseUrl(cbsBaseUrl)
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .build();
        this.timeout   = Duration.ofSeconds(timeoutSec);
        this.channelId = channelId;
    }

    public record CbsOpenResult(
        String     fdAccountNumber,
        String     cbsTransactionId,
        String     status
    ) {}

    public record CbsOpenRequest(
        String     channelId,
        String     customerId,
        String     sourceAccountNo,
        String     schemeCode,
        int        tenureMonths,
        BigDecimal principalAmount,
        String     currency,
        String     maturityInstruction
    ) {}

    /**
     * Submit FD open request to CBS.
     *
     * @param applicationId DBP application ID (sent as header for CBS audit trail)
     * @param payload       full FD payload from S3 / domain request
     */
    public Mono<CbsOpenResult> openFdAccount(String applicationId,
                                              Map<String, Object> payload) {
        CbsOpenRequest cbsReq = buildCbsRequest(payload);
        log.info("Submitting FD to CBS applicationId={} customerId={}",
            applicationId, cbsReq.customerId());

        return webClient.post()
            .uri("/cbs/api/v1/term-deposits")
            .header("X-Channel-Id",      channelId)
            .header("X-Application-Id",  applicationId)
            .header("X-Correlation-Id",  applicationId)
            .bodyValue(cbsReq)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, resp ->
                resp.bodyToMono(String.class)
                    .flatMap(body -> Mono.error(
                        new CbsRejectionException("CBS rejected FD: " + body))))
            .onStatus(HttpStatusCode::is5xxServerError, resp ->
                resp.bodyToMono(String.class)
                    .flatMap(body -> Mono.error(
                        new CbsUnavailableException("CBS server error: " + body))))
            .bodyToMono(CbsRawResponse.class)
            .timeout(timeout)
            .map(r -> new CbsOpenResult(r.tdAccountNumber(), r.transactionId(), r.status()))
            .doOnSuccess(r -> log.info("CBS FD opened applicationId={} fdAccountNo={} txnId={}",
                applicationId, r.fdAccountNumber(), r.cbsTransactionId()))
            .doOnError(e -> log.error("CBS open failed applicationId={}", applicationId, e));
    }

    // ── private helpers ────────────────────────────────────────────────────

    private CbsOpenRequest buildCbsRequest(Map<String, Object> payload) {
        return new CbsOpenRequest(
            channelId,
            (String)  payload.get("customerId"),
            (String)  payload.get("sourceAccountNo"),
            (String)  payload.get("schemeCode"),
            (Integer) payload.get("tenureMonths"),
            new BigDecimal(payload.get("principalAmount").toString()),
            (String)  payload.get("currency"),
            (String)  payload.get("maturityInstruction")
        );
    }

    private record CbsRawResponse(
        String tdAccountNumber,
        String transactionId,
        String status
    ) {}

    // ── CBS-specific exceptions (mapped to BPMN error codes in job worker) ─

    public static class CbsRejectionException extends RuntimeException {
        public CbsRejectionException(String msg) { super(msg); }
    }

    public static class CbsUnavailableException extends RuntimeException {
        public CbsUnavailableException(String msg) { super(msg); }
    }
}
