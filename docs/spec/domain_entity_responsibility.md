# Database Schema Data Dictionary & Table Responsibilities
This document provides a comprehensive overview of the responsibilities, domain purposes, and system roles for every database table across the Fintech Invoice Discounting Platform. The schema is organized into clean, modular, semantic subdomains using lower `snake_case` namespaces.

---

## 🏛️ 1. Deal & Core Finance Engine (`01_core.sql`)
This subdomain forms the transactional core of the platform, managing physical assets, trade listings, investor placement pools, and risk validation profiles.

### BC1: Listing & Invoice Domain
* **`deal_invoice`**
    * **Responsibility:** Serves as the immutable container for an ingested commercial invoice submitted by a supplier against a buyer.
    * **Core Data:** Tracks the invoice face value, Indian Invoice Registration Number (IRN), computed due date, and automated platform validation check outcomes.
* **`deal_listing`**
    * **Responsibility:** Tracks the public funding lifecycle of a validated invoice group exposed to marketplace investors. Acts as the central state machine coordinating BC2 (Subscription), BC4 (Settlement), BC5 (Assignment), and BC6 (Collections).
    * **Core Data:** Manages the target funding amount, committed capital volumes, settlement escrow credentials, snapshot fields frozen at `ready_for_review` (pricing, credit headroom), and the explicit maker-checker admin identifiers required to shift a deal to a live marketplace status. GoneLive requires Treasury & Settlement checker with valid MFA assertion.

### BC2: Subscription Domain
* **`sub_subscription`**
    * **Responsibility:** Models an individual investor's financial commitment to purchase a fractional share of a live marketplace deal listing.
    * **Core Data:** Enforces singular allocation constraints per investor per listing, controls state progressions from commitment to asset assignment execution, and captures distribution ledger allocations upon final asset maturity.

### BC3: Credit & Risk Underwriting Domain
* **`risk_buyer_profile`**
    * **Responsibility:** Dictates the maximum risk exposure bounds and creditworthiness terms permitted for an onboarding buyer company.
    * **Core Data:** Restricts the credit limit capacity, maximum allowable invoice tenor ceilings, and enforces dual administrative approval tracking records.
* **`risk_supplier_profile`**
    * **Responsibility:** Enforces maximum capital exposure caps for drawing supplier entities.
    * **Core Data:** Maps internal credit risk ratings and tracks historical administrative underwriting reviews.
* **`risk_pricing_policy`**
    * **Responsibility:** Stores the rules-as-data matrices defining platform interest yields and fee cut-offs based on buyer credit tiers and asset tenor ranges.
    * **Core Data:** Uses a self-referential tracking pattern (`superseded_by`) to ensure historical configurations remain accessible for processing active, in-flight investments.
* **`risk_default_case`**
    * **Responsibility:** Manages legal adjudication disputes, asset fraud write-offs, and structural default declarations when an asset goes unpaid.
    * **Core Data:** Locks down official classification results and requires absolute maker-checker separation for resolution execution.

### BC4: Settlement & Escrow Domain
* **`cash_virtual_account`**
    * **Responsibility:** Tracks unique, dedicated escrow collection accounts provisioned by banking API partners for automated payment mapping.
    * **Core Data:** Maps unique banking account numbers, routing codes (IFSC), and aggregates total incoming collections against expected target caps.
* **`cash_payout_instruction`**
    * **Responsibility:** Acts as the persistence gate for outbound commercial bank ledger transfers executed via banking endpoints. The `payout_instruction_id` doubles as the idempotency key (`client_instruction_id`) sent to the escrow vendor — the same UUID is reused, making retries safe.
    * **Core Data:** Formulates gross-to-net financial calculations (gross distributions, transaction fees, withholding tax deductions), enforces maker-checker separation (maker ≠ checker), and logs the mandatory Multi-Factor Authentication (MFA) tokens authorized by the clearing administrator. The `tds_snapshot` inside payload is immutable once the checker approves.
* **`cash_recon_ledger`**
    * **Responsibility:** Anchors the platform's daily cash ledger reconciliation routine against bank statements.
    * **Core Data:** Tracks absolute counters for matched inflows, flags unmatched items, and archives serialized JSON lists of exceptions.
* **`cash_remediation_case`**
    * **Responsibility:** Tracks exceptions requiring human intervention, such as failed bank updates, invalid webhook signatures, or unmatched investor transfers.
    * **Core Data:** Isolates problems via soft logical id references to decouple error handling from core processing paths.

### BC5: Legal & Digital Assignment Domain
* **`legal_master_agreement`**
    * **Responsibility:** Manages the baseline legal agreements (Master Investment/Supplier Agreements) onboarding external counterparties.
    * **Core Data:** Pairs cryptographic file hashes with digital stamp references and tracking statuses.
* **`legal_assignment_set`**
    * **Responsibility:** Tracks the collection of electronic signatures required to execute debt assignments from suppliers to investor pools.
    * **Core Data:** Acts as a completion gate for deal funding, tracking aggregate signatures before release keys open downstream payment paths.
* **`legal_signature_request`**
    * **Responsibility:** Tracks individual signature tasks routed to external electronic signing providers.
    * **Core Data:** Stores vendor workflow URLs, completion webhooks, and session retry histories.

### BC6: Collections Subsystem
* **`col_maturity_case`**
    * **Responsibility:** Serves as the tracking center for capital recovery when an asset passes its maturity date without payment.
    * **Core Data:** Computes exact days-past-due metrics and tracks cumulative legal recovery totals.
* **`col_action_log`**
    * **Responsibility:** Maintains an append-only timeline of operational collection tasks (such as reminder notices or legal demand letters).
    * **Core Data:** Connects action details with administrator IDs to ensure complete operational traceability.
* **`col_claim_case`**
    * **Responsibility:** Tracks formal indemnification or fraud claims filed against a supplier due to underlying trade disputes.
    * **Core Data:** Archives supporting evidence hashes and locks down recovery calculations.

---

## 👥 2. Counterparty & Account Profiles (`02_counterparty_platform.sql`)
This module handles identity onboarding, background validation logging, tax tracking, and role configurations for platform administrators.

### BC7: Investor Profile Domain
* **`inv_invite`**
    * **Responsibility:** Implements a controlled onboarding gateway by managing single-use investor registration tokens.
    * **Core Data:** Stores SHA-256 hashes of contact information to preserve data privacy and enforces a strict 14-day expiry policy.
* **`inv_account`**
    * **Responsibility:** Serves as the profile master for retail or entity investors on the platform.
    * **Core Data:** Maps a direct link to the core identity registry, logs verified tax identifiers (PAN), maps payment destinations, and schedules required periodic review updates.
* **`inv_suitability`**
    * **Responsibility:** Records mandatory investor risk-tolerance evaluation profiles to fulfill compliance requirements.
    * **Core Data:** Pairs evaluation questionnaire file references with risk flag indicators and logs manual administrative override justifications.

### BC8: Supplier Profile Domain
* **`sup_account`**
    * **Responsibility:** Acts as the profile master for onboarding commercial suppliers.
    * **Core Data:** Operates without standard login credentials during the early MVP phase, instead storing corporate profiles, tax identification numbers (PAN/GSTIN), and credit exposure limits.
* **`sup_agency_consent`**
    * **Responsibility:** Logs explicit legal authorization permitting platform administrators to handle administrative tasks on behalf of a supplier.
    * **Core Data:** Encapsulates allowed functional scopes and references verified digital files to establish a clear audit trail.
* **`sup_financial_profile`**
    * **Responsibility:** Organizes supplier financial statements, trade histories, and core buyer dependency tracking matrices.
    * **Core Data:** Enforces strict 90-day data-freshness timeframes (TTLs) on raw document sets to protect credit evaluation pipelines.

### BC9: Buyer Management Domain
* **`buyer_account`**
    * **Responsibility:** Serves as the master registry for corporate buyers whose trade payables back marketplace listings.
    * **Core Data:** Enforces Phase-1 operational modes (requiring per-invoice verification), holds corporate identifiers, and tracks credit allocation ceilings.
* **`buyer_ack_user`**
    * **Responsibility:** Manages profiles for a buyer's authorized operational users tasked with confirming trade payables.
    * **Core Data:** Ties access to an OTP-only login pattern, preventing the issuance of traditional static password credentials.
* **`buyer_payment_rule`**
    * **Responsibility:** Stores a buyer's verified payment routing paths used to handle maturity distributions.
    * **Core Data:** Employs a clean supersession chain to track account modifications without breaking the payment trail for active investments.

### BC10: Administrative IAM Domain
* **`admin_user`**
    * **Responsibility:** Manages identities and system access rules for internal operations teams.
    * **Core Data:** Maps internal identity joins, handles core access tokens, and requires active Multi-Factor Authentication (MFA) parameters before permitting access.
* **`admin_role_assignment`**
    * **Responsibility:** Implements granular Role-Based Access Control (RBAC) across administrative accounts.
    * **Core Data:** Enforces separation-of-duty parameters to block conflicting access pairs (such as a single account holding both underwriting and settlement clearance privileges).
* **`admin_deviation_log`**
    * **Responsibility:** Acts as a managed system override register when non-blocking administrative role pairings are activated.
    * **Core Data:** Enforces quarterly review tracking blocks that require super-admin validation to support internal operational monitoring.
* **`admin_sod_policy`**
    * **Responsibility:** Encapsulates separation-of-duty rule matrices as standard database rows rather than static code abstractions.
    * **Core Data:** Tracks active policy configurations, structural pair exclusions, and records the publishing admin accounts.

### BC11: Compliance Domain
* **`comp_aml_screening`**
    * **Responsibility:** Logs Anti-Money Laundering (AML) and Politically Exposed Person (PEP) background verification checks.
    * **Core Data:** Stores background match evaluation scores, verified hit details, and logs the manual compliance clearance review notes.
* **`comp_sar_case`**
    * **Responsibility:** Manages confidential internal Suspicious Activity Reports (SAR) flagged by compliance officers.
    * **Core Data:** Holds verification details and appends timestamped operational tracking updates within an isolated container.
* **`comp_refresh_schedule`**
    * **Responsibility:** Operates as a background scheduling engine to enforce automated annual compliance review timelines.
    * **Core Data:** Tracks scheduling statuses and logs completion timestamps to prevent verification profiles from lapsing.
* **`comp_kyc_file`**
    * **Responsibility:** Functions as the primary review bucket for multi-document identity validation files.
    * **Core Data:** Coordinates verification steps and isolates operational reviewer accounts via explicit verification rules.
* **`comp_spot_check`**
    * **Responsibility:** Archives the findings from independent compliance and data retention verification spot-checks.
    * **Core Data:** Connects verification period indices with verified audit document references.

### BC12: Tax Subsystem
* **`tax_year_profile`**
    * **Responsibility:** Aggregates Tax Deducted at Source (TDS) calculations and annual withholding parameters for an investor throughout a financial year.
    * **Core Data:** Tracks cumulative gross distributions, computed tax calculations, and logs official government filing issuance markers.
* **`tax_tds_deduction`**
    * **Responsibility:** Logs transaction-level tax withholding calculations for individual deal distribution allocations.
    * **Core Data:** Enforces a strict checking formula (`gross - tax - fee = net`) directly at the database layer to guarantee ledger integrity.
* **`tax_investor_statement`**
    * **Responsibility:** Formulates index paths for generated financial summaries and standard government tax filings.
    * **Core Data:** Maps lookups by pairing investor accounts with specific calculation periods.

### BC13: Third-Party Audit Subsystem
* **`audit_scope`**
    * **Responsibility:** Locks down strict data-access parameters for external review teams, including explicit timeline ranges, target tables, and classification limits.
    * **Core Data:** Enforces an absolute data lock—once a scope is used by an auditor account, its access rules become completely immutable.
* **`audit_account`**
    * **Responsibility:** Manages temporary, time-bound access profiles for external compliance review teams.
    * **Core Data:** Restricts operations to a read-only path, applies automated termination boundaries, and enforces maker-checker constraints for account setup.

---

## 🔐 3. Authentication Core Engine (`03_auth.sql`)
This module provides identity tracking and access control architecture, acting as the security gatekeeper for the data platform.

* **`auth_identity`**
    * **Responsibility:** Acts as the single, central security anchor for every physical person or principal accessing the platform.
    * **Core Data:** Connects user classes, tracks canonical case-insensitive emails (`citext`), logs mobile contact paths, and sets overall account access parameters.
* **`auth_credential`**
    * **Responsibility:** Manages underlying login credentials linked to core user accounts.
    * **Core Data:** Stores secure cryptographic secret hashes and holds third-party single sign-on (OAuth) identifier links.
* **`auth_mfa_factor`**
    * **Responsibility:** Enforces secondary verification challenges for high-privilege access paths.
    * **Core Data:** Manages encrypted security parameters, factor descriptions, and employs index constraints to block parallel unverified authenticators.
* **`auth_otp_challenge`**
    * **Responsibility:** Manages transient, one-time-password validation cycles used for logins and transaction verification. Exactly one active challenge per `(identity_id, purpose)` — issuing a new one supersedes the prior.
    * **Core Data:** Tracks challenge attempt counters, records short-lived validity horizons, and mints an `assertion_id` upon successful consumption. For `login_mfa` purpose, this `assertion_id` is written to `auth_session.mfa_assertion_id` and consulted by every admin state-changing command handler to enforce MFA freshness.
* **`auth_session`**
    * **Responsibility:** Controls real-time user session status and tracks application activity boundaries.
    * **Core Data:** Stores activity window expirations, captures client network addresses, and holds serialized claims parameters used to enforce system-wide tenant isolation.

---

## 🌐 4. Infrastructure & Integration ACL Engine (`04_generic_acl.sql`)
This layer handles cryptographic ledger tracing, platform system updates, and decouples internal data logic from external vendor API definitions.

* **`sys_audit_event`**
    * **Responsibility:** Acts as the platform's immutable, append-only security log, capturing all structural state modifications.
    * **Core Data:** Stores state transitions, handles correlation tokens, and uses custom database triggers to block updates or deletions, protecting data history.
* **`sys_notification_dispatch`**
    * **Responsibility:** Operates as an outbound communication queue handling customer alerts across system channels.
    * **Core Data:** Holds notification data bags, links recipients, tracks transmission statuses, and logs internal event tracking references.
* **`sys_document_object`**
    * **Responsibility:** Acts as an internal registry tracking files stored within secure, encrypted cloud repositories.
    * **Core Data:** Maps content headers, logs byte sizes, and stores security key tags to ensure data tracing across boundaries.
* **`gate_verification`**
    * **Responsibility:** Logs background validation data requests routed to regulatory networks and credit bureaus. Results are cached with per-data-type TTLs (PAN 12m, Bureau 30d, etc.) — the application checks this table before making a new vendor call.
    * **Core Data:** Stores third-party system data payloads, logs normalised extracted fields, captures HMAC verification timestamps, and tracks TTL expiry for scheduled stale-marking sweeps.
* **`gate_vendor_instruction`**
    * **Responsibility:** Coordinates transactional messaging tasks routed outward to clearing banks.
    * **Core Data:** Logs unique network tracking numbers and records security verification flags to guarantee transaction delivery.
* **`gate_inflow_observation`**
    * **Responsibility:** Collects incoming webhook notification items sent from banking partners when deposits hit virtual escrow endpoints.
    * **Core Data:** Enforces a unique constraint on incoming bank transaction references (UTRs) to prevent duplicate processing.
* **`gate_signature_session`**
    * **Responsibility:** Tracks digital document signature processes routed to licensed provider networks.
    * **Core Data:** Coordinates signature steps, manages routing keys, tracks retry counters, and logs completion timestamps.