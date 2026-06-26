#!/usr/bin/env bash
# End-to-end smoke of the money-flow spine against a locally-running dev app.
#   Prereq:  ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   (and Postgres up + migrated)
#   Run:     ./scripts/dev-smoke.sh
# Drives: login → create listing → ops-checks → ack → snapshot → go-live → subscribe → inflow(HMAC) →
#         assign → sign → disburse → mature. Uses the dev-seeded supplier/buyer/investor. See
#         docs/MANUAL_TESTING.md.
set -euo pipefail

HOST=${HOST:-http://localhost:8080}
PW=DevPass123!
SECRET=${PLATFORM_WEBHOOK_BANKING_SECRET:-dev-banking-webhook-secret-change-me}
FACE=100000000        # ₹10L invoice face value
TARGET=96027397       # funding_target for rate=1200bps, tenor=60d, fee=200bps (HALF_EVEN)

for t in curl jq openssl uuidgen; do command -v "$t" >/dev/null || { echo "missing tool: $t"; exit 1; }; done

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ver_of() { jq -r '.aggregate_version'; }

login() {  # login EMAIL -> bearer
  local email=$1 ch code
  ch=$(curl -s "$HOST/auth/login/password" -H 'Content-Type: application/json' \
        -d "{\"email\":\"$email\",\"password\":\"$PW\"}" | jq -r .challenge_id)
  code=$(curl -s "$HOST/dev/last-otp?email=$email" | jq -r .code)
  curl -s "$HOST/auth/login/verify-otp" -H 'Content-Type: application/json' \
        -d "{\"challenge_id\":\"$ch\",\"code\":\"$code\"}" | jq -r .bearer
}

# post BEARER PATH BODY [VERSION]  -> echoes the JSON response (fails the script on a 4xx/5xx error_code)
post() {
  local bearer=$1 path=$2 body=${3:-} ver=${4:-} resp
  local hdrs=(-H "Authorization: Bearer $bearer" -H "X-Command-Id: $(uuidgen)")
  [ -n "$body" ] && hdrs+=(-H 'Content-Type: application/json')
  [ -n "$ver" ]  && hdrs+=(-H "X-Aggregate-Version: $ver")
  resp=$(curl -s -X POST "$HOST$path" "${hdrs[@]}" ${body:+-d "$body"})
  if echo "$resp" | jq -e '.error_code' >/dev/null 2>&1; then
    echo "  ✗ $path -> $(echo "$resp" | jq -c '{error_code,message}')" >&2; exit 1
  fi
  echo "$resp"
}

say "login ops / treasury / treasury2"
OPS=$(login ops@dev.local); TRE=$(login treasury@dev.local); TRE2=$(login treasury2@dev.local)
echo "  ops=${OPS:0:8}…  treasury=${TRE:0:8}…  treasury2=${TRE2:0:8}…"

say "seed-info"
SEED=$(curl -s "$HOST/dev/seed-info")
SUP=$(echo "$SEED" | jq -r .supplier_id); BUY=$(echo "$SEED" | jq -r .buyer_id); INV=$(echo "$SEED" | jq -r .investor_id)
echo "  supplier=$SUP  buyer=$BUY  investor=$INV"

say "create listing"
R=$(post "$OPS" /listings \
  "{\"supplier_id\":\"$SUP\",\"buyer_id\":\"$BUY\",\"invoice_number\":\"INV-$(uuidgen)\",\"face_value_paise\":$FACE,\"invoice_date\":\"2026-06-01\",\"tenor_days\":60}")
L=$(echo "$R" | jq -r .aggregate_id); V=$(echo "$R" | ver_of); echo "  listing=$L v=$V"

say "ops checks (start → 7 checks → complete)"
V=$(post "$OPS" "/listings/$L/start-ops-checks" "" "$V" | ver_of)
V=$(post "$OPS" "/listings/$L/record-ops-check" '{"check_name":"irn_validity"}' "$V" | ver_of)
for c in eway_bill_match buyer_supplier_relationship duplicate_check supplier_exposure_cap buyer_limit_headroom document_completeness; do
  V=$(post "$OPS" "/listings/$L/record-ops-check" "{\"check_name\":\"$c\",\"outcome\":\"passed\"}" "$V" | ver_of)
done
V=$(post "$OPS" "/listings/$L/complete-ops-checks" "" "$V" | ver_of); echo "  → awaiting_acknowledgment (v=$V)"

say "buyer ack → snapshot → go-live"
V=$(post "$OPS" "/listings/$L/record-buyer-ack" '{"outcome":"acknowledged","method":"email"}' "$V" | ver_of)
V=$(post "$OPS" "/listings/$L/snapshot-and-ready" '{"rate_bps":1200}' "$V" | ver_of)
V=$(post "$TRE" "/listings/$L/approve-go-live" "" "$V" | ver_of); echo "  → live (v=$V)"

VA=$(curl -s "$HOST/listings/$L" -H "Authorization: Bearer $OPS" | jq -r .va_id); echo "  va_id=$VA"

say "subscribe full funding_target → fully_funded"
post "$OPS" "/listings/$L/subscriptions/commit" "{\"investor_id\":\"$INV\",\"amount_paise\":$TARGET}" >/dev/null
echo "  committed $TARGET"

say "inflow webhook (HMAC) → confirmed"
BODY="{\"va_id\":\"$VA\",\"amount_paise\":$TARGET,\"utr\":\"UTR-$(uuidgen)\",\"event_id\":\"evt-$(uuidgen)\"}"
TS=$(( $(date +%s) * 1000 ))
SIG=$(printf '%s' "$TS.$BODY" | openssl dgst -sha256 -hmac "$SECRET" | sed 's/^.* //')
curl -s -o /dev/null -w "  webhook HTTP %{http_code}\n" -X POST "$HOST/webhooks/banking/stub-escrow/inflow.received" \
  -H "X-Timestamp: $TS" -H "X-Signature: $SIG" -H 'Content-Type: application/json' -d "$BODY"

say "assignment → all_signed (C27 gate)"
post "$OPS" "/listings/$L/assignment-set/request" "" >/dev/null
post "$OPS" "/listings/$L/assignment-set/complete-signing" "{\"investor_id\":\"$INV\"}" >/dev/null
echo "  → all_signed"

say "disburse (maker treasury, checker treasury2) → disbursed"
post "$TRE"  "/listings/$L/disbursement/draft" "" >/dev/null
post "$TRE2" "/listings/$L/disbursement/approve" "" >/dev/null

say "maturity (full repayment) → matured_payment_received"
post "$OPS" "/listings/$L/record-maturity" "{\"amount_paise\":$FACE,\"utr\":\"UTR-$(uuidgen)\"}" >/dev/null

say "FINAL STATE"
curl -s "$HOST/listings/$L" -H "Authorization: Bearer $OPS" | jq '{status,funding_target,va_id,aggregate_version}'
curl -s "$HOST/listings/$L/assignment-set" -H "Authorization: Bearer $OPS" | jq '{assignment:.status,all_signed}'
printf '\n\033[1;32m✓ golden path complete for listing %s  (expect status=matured_payment_received)\033[0m\n' "$L"
