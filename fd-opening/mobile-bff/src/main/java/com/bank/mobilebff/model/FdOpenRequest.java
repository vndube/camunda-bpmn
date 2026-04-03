package com.bank.mobilebff.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Incoming FD opening request from Mobile App.
 * This is the FULL payload – persisted to S3.
 * Only a slim subset is forwarded to Zeebe.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FdOpenRequest(

    // Customer context
    @NotBlank String customerId,         // will be redacted before Zeebe
    @NotBlank String sourceAccountNo,
    @NotBlank String channelId,          // MOBILE

    // FD parameters
    @NotBlank  String schemeCode,
    @NotNull   @Positive Integer tenureMonths,
    @NotNull   @DecimalMin("1000.00") BigDecimal principalAmount,
    @NotBlank  String currency,
    @NotBlank  String maturityInstruction, // AUTO_RENEW | CREDIT_TO_ACCOUNT

    // Nomination (optional)
    NomineeDetails nomineeDetails,

    // Meta – populated by BFF
    String applicationId,
    String journeyType,               // SELF_SERVICE | ASSISTED
    Instant requestTimestamp
) {
    public record NomineeDetails(
        String nomineeName,
        String nomineeRelation,
        String nomineeDob
    ) {}
}
