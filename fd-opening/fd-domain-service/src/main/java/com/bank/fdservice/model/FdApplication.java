package com.bank.fdservice.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DBP application state record – persisted in the FD domain DB.
 * Tracks lifecycle of an FD opening request within DBP.
 */
public record FdApplication(
    String        applicationId,
    String        customerId,          // masked for logs; full in DB (encrypted)
    String        sourceAccountNo,
    String        schemeCode,
    Integer       tenureMonths,
    BigDecimal    principalAmount,
    String        currency,
    String        maturityInstruction,
    String        journeyType,         // SELF_SERVICE | ASSISTED
    String        channelId,
    FdStatus      status,
    String        fdAccountNumber,     // set after CBS open
    String        cbsTransactionId,
    String        failureReason,
    Instant       createdAt,
    Instant       updatedAt
) {
    public enum FdStatus {
        INITIATED,
        DRAFT,           // self-service: persisted for review
        SUBMITTED,       // customer confirmed on review page
        PROCESSING,      // CBS call in-flight
        COMPLETE,        // FD opened in CBS
        FAILED
    }
}
