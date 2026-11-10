#!/usr/bin/env bash
# Assert the public contract against a real instance; every mismatch exits nonzero.
set -euo pipefail
exec python3 - "${1:-http://localhost:8080}" <<'PY'
import json
import sys
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

base = sys.argv[1].rstrip('/')
def check(condition, message):
    if not condition:
        raise SystemExit('SMOKE FAILED: ' + message)

def request(path, expected, body=None, key=None):
    headers = {'Content-Type': 'application/json'}
    if key:
        headers['Idempotency-Key'] = key
    req = Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        response = urlopen(req, timeout=15)
    except HTTPError as error:
        response = error
    with response:
        value = json.load(response)
        check(response.status == expected, f'{path}: expected {expected}, got {response.status}: {value}')
        return value, response.headers

alice, _ = request('/accounts', 201, {'owner': 'alice', 'currency': 'USD', 'initialBalance': '100.00'})
bob, _ = request('/accounts', 201, {'owner': 'bob', 'currency': 'EUR'})
check(alice['balance'] == '100.00' and alice['currency'] == 'USD', 'opening sender balance')
check(bob['balance'] == '0.00' and bob['currency'] == 'EUR', 'opening receiver balance')
a, b = alice['id'], bob['id']
key = 'smoke-' + str(uuid4())
body = {'sourceAccountId': a, 'destinationAccountId': b, 'amount': '25.00', 'currency': 'USD'}
first, headers = request('/transfers', 201, body, key)
check(headers.get('Idempotency-Replayed') == 'false', 'first request replay header')
check(first['status'] == 'COMPLETED' and first['sourceAccountId'] == a and first['destinationAccountId'] == b, 'transfer identity/status')
check(first['sourceAmount'] == {'amount': '25.00', 'currency': 'USD'}, 'source amount')
check(first['destinationAmount'] == {'amount': '23.00', 'currency': 'EUR'} and first['fxRate'] == '0.92000000', 'FX result')
replay, headers = request('/transfers', 200, body, key)
check(headers.get('Idempotency-Replayed') == 'true' and replay == first, 'replay body/header')
conflict, _ = request('/transfers', 409, dict(body, amount='26.00'), key)
check(conflict['code'] == 'idempotency_key_reused', 'conflict error code')
for account, balance, currency in [(a, '75.00', 'USD'), (b, '23.00', 'EUR')]:
    value, _ = request('/accounts/' + account, 200)
    check(value['id'] == account and value['balance'] == balance and value['currency'] == currency, 'final balance ' + account)
statement, _ = request('/accounts/' + a + '/statement', 200)
check(len(statement['entries']) == 2 and statement['nextCursor'] is None, 'statement cardinality')
entry = statement['entries'][0]
check(entry['transferId'] == first['id'] and entry['amount'] == {'amount': '-25.00', 'currency': 'USD'}
      and entry['balanceAfter'] == {'amount': '75.00', 'currency': 'USD'}, 'statement debit')
health, _ = request('/health', 200)
check(health == {'status': 'up', 'postgres': 'up', 'redis': 'up'}, 'healthy dependencies')
print('SMOKE PASSED: exact statuses, replay, conflict, FX, balances, statement and health')
PY
