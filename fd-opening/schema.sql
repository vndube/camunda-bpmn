-- ─────────────────────────────────────────────────────────────────────────
-- DBP FD Application State Schema
-- Database: PostgreSQL 15+
-- ─────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS fd_applications (
    application_id          VARCHAR(64)    PRIMARY KEY,
    customer_id             VARCHAR(64)    NOT NULL,          -- encrypted at rest
    source_account_no       VARCHAR(30)    NOT NULL,
    scheme_code             VARCHAR(20)    NOT NULL,
    tenure_months           INTEGER        NOT NULL,
    principal_amount        NUMERIC(18,2)  NOT NULL,
    currency                CHAR(3)        NOT NULL DEFAULT 'INR',
    maturity_instruction    VARCHAR(30)    NOT NULL,
    journey_type            VARCHAR(20)    NOT NULL,          -- SELF_SERVICE | ASSISTED
    channel_id              VARCHAR(20)    NOT NULL,
    status                  VARCHAR(20)    NOT NULL,          -- INITIATED | DRAFT | SUBMITTED | PROCESSING | COMPLETE | FAILED
    fd_account_number       VARCHAR(30),                      -- set after CBS open
    cbs_transaction_id      VARCHAR(64),
    failure_reason          TEXT,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_fd_app_customer_status
    ON fd_applications (customer_id, status);

CREATE INDEX IF NOT EXISTS idx_fd_app_journey_status
    ON fd_applications (journey_type, status);

CREATE INDEX IF NOT EXISTS idx_fd_app_channel_created
    ON fd_applications (channel_id, created_at DESC);

-- ─────────────────────────────────────────────────────────────────────────
-- Auto-update updated_at on row change
-- ─────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_fd_applications_updated_at
    BEFORE UPDATE ON fd_applications
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
