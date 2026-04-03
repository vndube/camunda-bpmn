package com.bank.fdservice.service;

import com.bank.fdservice.model.FdApplication;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Reactive R2DBC repository for FdApplication state.
 * Manages the DBP application lifecycle: INITIATED → DRAFT → SUBMITTED → COMPLETE/FAILED.
 */
@Repository
public class FdApplicationRepository {

    private final DatabaseClient db;

    public FdApplicationRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Void> insert(FdApplication app) {
        return db.sql("""
                INSERT INTO fd_applications (
                    application_id, customer_id, source_account_no, scheme_code,
                    tenure_months, principal_amount, currency, maturity_instruction,
                    journey_type, channel_id, status, created_at, updated_at
                ) VALUES (
                    :applicationId, :customerId, :sourceAccountNo, :schemeCode,
                    :tenureMonths, :principalAmount, :currency, :maturityInstruction,
                    :journeyType, :channelId, :status, :createdAt, :updatedAt
                )
                """)
            .bind("applicationId",      app.applicationId())
            .bind("customerId",         app.customerId())
            .bind("sourceAccountNo",    app.sourceAccountNo())
            .bind("schemeCode",         app.schemeCode())
            .bind("tenureMonths",       app.tenureMonths())
            .bind("principalAmount",    app.principalAmount())
            .bind("currency",           app.currency())
            .bind("maturityInstruction",app.maturityInstruction())
            .bind("journeyType",        app.journeyType())
            .bind("channelId",          app.channelId())
            .bind("status",             app.status().name())
            .bind("createdAt",          app.createdAt())
            .bind("updatedAt",          app.updatedAt())
            .fetch().rowsUpdated().then();
    }

    public Mono<Void> updateStatus(String applicationId,
                                    FdApplication.FdStatus status) {
        return db.sql("""
                UPDATE fd_applications
                   SET status = :status, updated_at = :updatedAt
                 WHERE application_id = :applicationId
                """)
            .bind("applicationId", applicationId)
            .bind("status",        status.name())
            .bind("updatedAt",     Instant.now())
            .fetch().rowsUpdated().then();
    }

    public Mono<Void> updateComplete(String applicationId,
                                      String fdAccountNumber,
                                      String cbsTransactionId) {
        return db.sql("""
                UPDATE fd_applications
                   SET status = 'COMPLETE',
                       fd_account_number  = :fdAccountNumber,
                       cbs_transaction_id = :cbsTransactionId,
                       updated_at         = :updatedAt
                 WHERE application_id = :applicationId
                """)
            .bind("applicationId",   applicationId)
            .bind("fdAccountNumber", fdAccountNumber)
            .bind("cbsTransactionId",cbsTransactionId)
            .bind("updatedAt",       Instant.now())
            .fetch().rowsUpdated().then();
    }

    public Mono<FdApplication> findById(String applicationId) {
        return db.sql("""
                SELECT * FROM fd_applications WHERE application_id = :applicationId
                """)
            .bind("applicationId", applicationId)
            .map(this::mapRow)
            .one();
    }

    // ── row mapper ────────────────────────────────────────────────────────

    private FdApplication mapRow(Row row, RowMetadata meta) {
        return new FdApplication(
            row.get("application_id",       String.class),
            row.get("customer_id",          String.class),
            row.get("source_account_no",    String.class),
            row.get("scheme_code",          String.class),
            row.get("tenure_months",        Integer.class),
            row.get("principal_amount",     BigDecimal.class),
            row.get("currency",             String.class),
            row.get("maturity_instruction", String.class),
            row.get("journey_type",         String.class),
            row.get("channel_id",           String.class),
            FdApplication.FdStatus.valueOf(row.get("status", String.class)),
            row.get("fd_account_number",    String.class),
            row.get("cbs_transaction_id",   String.class),
            row.get("failure_reason",       String.class),
            row.get("created_at",           Instant.class),
            row.get("updated_at",           Instant.class)
        );
    }
}
