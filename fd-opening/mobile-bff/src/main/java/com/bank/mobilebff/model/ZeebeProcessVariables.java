package com.bank.mobilebff.model;

import java.time.Instant;

/**
 * Minimal variables sent to Camunda Zeebe.
 * Full payload lives in S3 only – never in Zeebe.
 */
public record ZeebeProcessVariables(
    String applicationId,
    String journeyType,              // SELF_SERVICE | ASSISTED
    String redactedCustomerId,       // e.g. "CUST****789"
    String channelId,
    String status,                   // INITIATED
    Instant initiatedAt
) {
    /** Mask all but last 3 chars of customerId */
    public static String redact(String customerId) {
        if (customerId == null || customerId.length() < 4) return "****";
        return customerId.substring(0, 4) + "****"
               + customerId.substring(customerId.length() - 3);
    }
}
