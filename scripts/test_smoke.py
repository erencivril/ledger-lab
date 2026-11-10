"""Contract tests for the smoke command, including deliberate bad-server controls."""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import threading
import unittest


class SmokeContractTest(unittest.TestCase):
    def run_smoke(self, fault=None):
        class Handler(BaseHTTPRequestHandler):
            accounts = 0
            payments = 0
            def log_message(self, *args):
                pass
            def do_GET(self):
                if self.path == '/health':
                    self.reply(200, {'status': 'up' if fault == 'health' else 'ok', 'postgres': 'up', 'redis': 'up'})
                elif '/statement' in self.path:
                    self.reply(200, {'entries': [] if fault == 'statement' else [
                        {'transferId': 'payment', 'amount': '-25.00', 'currency': 'USD', 'balanceAfter': '75.00'},
                        {'transferId': 'opening', 'amount': '100.00', 'currency': 'USD', 'balanceAfter': '100.00'}], 'nextCursor': None})
                else:
                    sender = self.path.endswith('/sender')
                    self.reply(200, {'id': 'sender' if sender else 'receiver',
                        'balance': ('74.00' if fault == 'balance' else '75.00') if sender else '23.00',
                        'currency': 'USD' if sender else 'EUR'})
            def do_POST(self):
                self.rfile.read(int(self.headers.get('Content-Length', 0)))
                if self.path == '/accounts':
                    Handler.accounts += 1
                    sender = Handler.accounts == 1
                    self.reply(201, {'id': 'sender' if sender else 'receiver',
                        'balance': '100.00' if sender else '0.00', 'currency': 'USD' if sender else 'EUR'})
                else:
                    Handler.payments += 1
                    n = Handler.payments
                    if n == 3:
                        self.reply(200 if fault == 'conflict_status' else 409,
                                   {'code': 'wrong' if fault == 'conflict_code' else 'idempotency_key_reused'})
                    else:
                        self.reply((200 if fault == 'first_status' else 201) if n == 1 else (201 if fault == 'replay_status' else 200),
                            {'id': 'wrong' if n == 2 and fault == 'replay_id' else 'payment',
                             'sourceAccountId': 'sender', 'destinationAccountId': 'receiver',
                             'sourceAmount': {'amount': '25.00', 'currency': 'USD'},
                             'destinationAmount': {'amount': '23.00', 'currency': 'EUR'},
                             'fxRate': '0.92000000', 'status': 'COMPLETED'},
                             {'Idempotency-Replayed': 'true' if n == 2 else 'false'})
            def reply(self, status, body, headers=None):
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                for k, v in (headers or {}).items():
                    self.send_header(k, v)
                self.end_headers()
                self.wfile.write(json.dumps(body).encode())
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            script = Path(__file__).with_name('smoke.sh')
            return subprocess.run([str(script), f'http://127.0.0.1:{server.server_port}'], capture_output=True, text=True, timeout=20)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_valid_contract_passes(self):
        result = self.run_smoke()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_bad_status_body_or_balance_fails(self):
        for fault in ['first_status', 'replay_status', 'replay_id', 'conflict_status', 'conflict_code', 'balance', 'statement', 'health']:
            with self.subTest(fault=fault):
                result = self.run_smoke(fault)
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
