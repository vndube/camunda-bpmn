# FD Opening — Architecture & Developer Guide

## Module Structure

```
fd-opening/
├── bpmn/                        Camunda BPMN process definition
├── mobile-bff/                  Mobile BFF (Spring WebFlux)
├── job-worker/                  Zeebe Job Workers (Spring Boot)
├── fd-domain-service/           FD Domain Service (Spring WebFlux + R2DBC)
└── docker-compose.yml           Local dev stack
```

---

## Architecture Principles

| Principle | Implementation |
|---|---|
| Payload isolation from Zeebe | Full FD payload stored in S3; only `applicationId`, `journeyType`, `redactedCustomerId`, `status` passed as Zeebe variables |
| Single BPMN for all channels | `journeyType` gateway drives SELF_SERVICE vs ASSISTED path |
| BFF → Zeebe only | BFFs interact exclusively with Zeebe via Zeebe Java client. No direct domain service calls from BFF |
| Job Worker as coordinator | Workers fetch payload from S3, delegate to Domain Service, return result to Zeebe |
| Reactive throughout | Spring WebFlux + R2DBC in BFF and Domain Service; blocking S3/Zeebe SDK calls wrapped on `boundedElastic` |

---

## FD Opening Flow

### Self-Service (Mobile / RIB)

```
Mobile App
  → POST /api/v1/fd/initiate   (Mobile BFF)
      1. Enrich request (applicationId, timestamp)
      2. Check duplicate active journey in Zeebe
      3. Persist full payload → S3 (fd-requests/{applicationId}.json)
      4. Create Zeebe process instance (minimal vars only)
      ← { applicationId, processInstanceKey }

Zeebe executes:
  Task: fd-validate
    JW fetches payload from S3 → calls Domain Service /validate
    Domain Service: KYC check → Account check → Scheme check
    JW completes job (validationStatus=PASSED) or throws BPMN error

  Gateway: journeyType == SELF_SERVICE
    Task: fd-persist-draft
      JW calls Domain Service → sets DBP state = DRAFT

  Intermediate Catch Event: waits for "customer-fd-submit" message

Mobile App (review screen)
  → POST /api/v1/fd/{applicationId}/submit  (Mobile BFF)
      Publishes "customer-fd-submit" message to Zeebe (correlationKey=applicationId)

Zeebe resumes:
  Task: fd-cbs-open
    JW fetches payload from S3 → calls Domain Service /cbs-open
    Domain Service → CBS Adapter → CBS
    JW completes job (status=COMPLETE, fdAccountNumber=...)
```

### Assisted Service (Branch)

```
Branch Terminal
  → POST /api/v1/fd/initiate  (Assisted BFF)
      1. Persist payload → S3
      2. Create Zeebe process (journeyType=ASSISTED)
      No DRAFT state, no review page

Zeebe executes:
  Task: fd-validate  →  Gateway: ASSISTED  →  Task: fd-cbs-open (directly)
```

---

## Zeebe Process Variables (minimal set)

| Variable | Type | Description |
|---|---|---|
| `applicationId` | String | Unique ID; S3 key prefix |
| `journeyType` | String | `SELF_SERVICE` or `ASSISTED` |
| `redactedCustomerId` | String | e.g. `CUST****789` — PII-safe |
| `channelId` | String | `MOBILE`, `RIB`, `BRANCH` |
| `status` | String | `INITIATED` → `DRAFT` → `COMPLETE` / `FAILED` |
| `fdAccountNumber` | String | Set by fd-cbs-open worker on success |
| `cbsTransactionId` | String | Set by fd-cbs-open worker on success |

Full payload stays in S3 only — never in Zeebe.

---

## DBP Application States

```
INITIATED → DRAFT (self-service only) → SUBMITTED → PROCESSING → COMPLETE
                                                               ↘ FAILED
INITIATED → PROCESSING → COMPLETE   (assisted — skips DRAFT/SUBMITTED)
```

---

## Running Locally

### Prerequisites
- Docker & Docker Compose
- Java 21, Maven 3.9+

### Start infrastructure
```bash
docker-compose up -d zeebe elasticsearch operate postgres localstack
```

### Create S3 bucket in LocalStack
```bash
aws --endpoint-url=http://localhost:4566 s3 mb s3://fd-payload-bucket --region ap-south-1
```

### Deploy BPMN to Zeebe
```bash
zbctl deploy bpmn/fd-opening-process.bpmn \
  --address localhost:26500 \
  --insecure
```

### Start services
```bash
# Terminal 1 — Domain Service
cd fd-domain-service && mvn spring-boot:run

# Terminal 2 — Job Worker
cd job-worker && mvn spring-boot:run

# Terminal 3 — Mobile BFF
cd mobile-bff && mvn spring-boot:run
```

### Test the happy path
```bash
# 1. Initiate FD journey
curl -s -X POST http://localhost:8080/api/v1/fd/initiate \
  -H "Content-Type: application/json" \
  -d '{
    "customerId":          "CUST00012345",
    "sourceAccountNo":     "SB10001234567",
    "channelId":           "MOBILE",
    "schemeCode":          "FD-REGULAR-12M",
    "tenureMonths":        12,
    "principalAmount":     50000.00,
    "currency":            "INR",
    "maturityInstruction": "AUTO_RENEW"
  }'
# Response: { "applicationId": "...", "processInstanceKey": 12345 }

# 2. Submit from review page (after customer reviews)
curl -s -X POST http://localhost:8080/api/v1/fd/{applicationId}/submit
```

### Monitor in Camunda Operate
Open http://localhost:8081 → sign in (demo/demo) → view process instances.

---

## Key Dependencies

| Component | Version |
|---|---|
| Spring Boot | 3.3.0 |
| Camunda Zeebe | 8.5.0 |
| Spring Zeebe Starter | 8.5.0 |
| AWS SDK v2 | 2.25.0 |
| Java | 21 |
| PostgreSQL | 15 |
