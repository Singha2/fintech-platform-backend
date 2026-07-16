# Postman — Fintech Platform (dev)

A one-collection API test kit. This guide is written for a **junior developer / intern**: follow it
top-to-bottom and you can exercise every document/invoice-upload scenario by clicking **Send**.

The collection file is `postman/fintech-platform.postman_collection.json`. Import it into Postman
(**Import → File**). Everything is pre-wired — bearers, ids, and command-ids are captured automatically
by test scripts, so you rarely type an id by hand.

---

## 1. Prerequisite: the backend running in the dev profile

Start the app the usual way — see **`docs/MANUAL_TESTING.md` §0** for the authoritative steps. The only
requirement for this collection is that it's up in the **dev profile** (`-Dspring-boot.run.profiles=dev`),
which enables the `/dev` helpers and seeds the admins + a supplier/buyer/investor the requests below use.

The API is at **`http://localhost:8080/api/v1`** — already set as the `baseUrl` collection variable. Confirm
it's reachable by running any Login below.

---

## 2. Log in (one click)

Open **`1 · Login`** and **Send `Login: Ops`**. A pre-request script does password → fetch-OTP →
verify-otp for you and stores `bearer_ops`. Do the same for **`Login: Compliance`** (needed for the
"wrong role → 403" tests). All dev admins share the password `DevPass123!`.

Then, for the invoice folder, open **`0 · Dev helpers → seed-info`** and **Send** — it captures the
seeded `supplier_id` and `buyer_id` you'll list an invoice against.

---

## 3. The document scenarios — folder `7 · Documents & Invoice Upload`

> **How the file upload works here:** the PDF body is just plain text starting with `%PDF-1.4`. The server
> keys off the `application/pdf` **content-type header**, not the actual bytes, so you never need to pick a
> real file — everything is reproducible with **Send**.

Run the sub-folders in this order. Each request has assertions in the **Test Results** tab — green = pass.

| Folder | What it proves | Prereqs |
|---|---|---|
| **A · Generic upload — happy path** | initiate → upload → finalize → get → download | Login: Ops |
| **B · Generic — negatives & idempotency** | replay is a no-op; bad body/header/id rejected; bytes sealed until stored | Login: Ops |
| **C · Invoice upload & attach** | upload a PDF and attach it to a listing's invoice; download gate | Login: Ops + seed-info |
| **D · Invoice attach — negatives** | not-stored / unknown doc / unknown listing / missing field / wrong role / duplicate | run **C** first |
| **E · Replace / supersede** | swap the active invoice artifact (old → superseded, new → active) | run **C** first |
| **F · KYC document upload** | same pattern over a KYC file | needs a `kyc_file_id` (see below) |

**Recommended first run:** `A` (5 requests) → `C` (8) → `E` (5), then dip into `B` and `D` for the
rejection cases. You can also select the whole `7 ·` folder and use Postman's **Run** button to fire them
in sequence.

### Scenario matrix (what each negative asserts)

| Request | Expected |
|---|---|
| B3 Initiate without `declared_size` | `400 validation_failed` |
| B4 Initiate without `X-Command-Id` | `400 missing_header` |
| B5 Finalize before any upload | `400` (no bytes to store) |
| B6 Upload a non-PDF content-type | `415` unsupported media type |
| B7 Upload to an unknown document | `404 not_found` |
| B8 Finalize an unknown document | `404 not_found` |
| B9 Download before finalize | `404` (bytes are sealed until `stored`) |
| B10 Get an unknown document | `404 not_found` |
| D1 Attach a not-yet-stored doc | `400 validation_failed` (DOC.5 — must be a stored PDF) |
| D2 Attach an unknown document | `400 validation_failed` |
| D3 Attach to an unknown listing | `404 not_found` |
| D4 Attach with missing `document_id` | `400 validation_failed` |
| D5 Attach as **Compliance** (wrong role) | `403 role_not_held` (segregation of duties) |
| D6 Attach a **second** active artifact | `400` (one active artifact per invoice — DOC.1) |

### The download gate (teaching moment)

`C8 Download before live → 400` is **expected**. Invoice PDFs are downloadable only once the listing
reaches the live-set. To see a real `200` download, run the **`2 · Golden Path`** folder to take a listing
live, then `GET /listings/{{listing_id}}/invoice-documents/{{invoice_doc_id}}/content`.

### Folder F (KYC) — advanced

The KYC attach flow needs an existing `kyc_file_id`, which is created by onboarding (`5 · Supplier
onboarding` up to *Submit KYC*). There's no dev endpoint that returns the id, so set the `kyc_file_id`
collection variable by hand (from the DB: `SELECT kyc_file_id FROM comp_kyc_file ORDER BY ... ;`) before
running F1. The uploads themselves reuse the generic flow from folder A automatically.

---

## 4. How the auto-wiring works (so you can debug it)

- **Bearers** — the Login requests store `bearer_ops` / `bearer_compliance` etc. as collection variables;
  every request sends `Authorization: Bearer {{bearer_ops}}`.
- **`X-Command-Id`** — every state-changing command needs a fresh idempotency key. Requests use Postman's
  `{{$guid}}` to generate one. The idempotency tests (B1/B2, C5/C6) deliberately **reuse** the same
  `idem_cmd` variable across two sends to prove a replay is a no-op.
- **Captured ids** — `document_id`, `invoice_doc_id`, `listing_id`, … are set by each request's **Tests**
  script and consumed by the next. If a downstream request 404s, re-send the request that captures its id.
- **Setup helpers** — a couple of negatives need pre-existing state (a pending doc, a second stored doc).
  Their **Pre-request Script** calls `initPendingDoc()` / `uploadStoredPdf(varName)` — small helpers
  defined once at the collection level (Collection → Edit → Pre-request Scripts). Watch the Postman Console
  (**View → Show Postman Console**) if a helper misfires.

---

## 5. Run it headless (optional, for CI or a quick sweep)

```bash
npm i -g newman
newman run postman/fintech-platform.postman_collection.json --folder "7 · Documents & Invoice Upload (M18/M19)"
```

> Note: Newman runs requests in order but does **not** log you in unless the Login requests are in the run.
> For a folder-only run, first run the `1 · Login` and `0 · Dev helpers` folders (or run the whole
> collection) so the bearers/ids are populated in the environment.

These same scenarios are also covered by the automated integration tests
(`DocumentApiTest`, `InvoiceArtifactTest`) — the Postman collection is the by-hand, click-to-learn mirror.
