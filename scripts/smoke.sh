#!/usr/bin/env bash
# End-to-end check against a running instance: two accounts, a transfer, an idempotent retry,
# a conflicting retry, and the statement.
set -euo pipefail
BASE="${1:-http://localhost:8080}"
KEY="smoke-$(date +%s)-$RANDOM"

json() { python3 -c "import sys, json; print(json.load(sys.stdin)$1)"; }
post() { curl -sS -X POST "$BASE$1" -H 'Content-Type: application/json' "${@:3}" -d "$2"; }

A=$(post /accounts '{"owner":"alice","currency":"USD","initialBalance":"100.00"}' | json "['id']")
B=$(post /accounts '{"owner":"bob","currency":"EUR"}' | json "['id']")
echo "alice=$A bob=$B"

BODY="{\"sourceAccountId\":\"$A\",\"destinationAccountId\":\"$B\",\"amount\":\"25.00\",\"currency\":\"USD\"}"

echo "--- first request"
curl -sS -i -X POST "$BASE/transfers" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d "$BODY" | tee /tmp/smoke-first.txt | grep -Ei '^(HTTP|idempotency-replayed)|"id"'
echo "--- retry with the same key and body (expect 200, replayed)"
curl -sS -i -X POST "$BASE/transfers" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d "$BODY" | grep -Ei '^(HTTP|idempotency-replayed)'
echo "--- same key, different amount (expect 409)"
curl -sS -i -X POST "$BASE/transfers" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d "${BODY/25.00/26.00}" | grep -Ei '^HTTP|"code"'

echo "--- balances (expect alice 75.00 USD, bob 23.00 EUR: one transfer only)"
curl -sS "$BASE/accounts/$A" | json "['balance']"
curl -sS "$BASE/accounts/$B" | json "['balance']"
echo "--- alice statement"
curl -sS "$BASE/accounts/$A/statement"; echo
echo "--- health"
curl -sS "$BASE/health"; echo
