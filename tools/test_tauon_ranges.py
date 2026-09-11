#!/usr/bin/env python3
"""Exercise actual Tauon send_file over loopback, without importing/running Tauon."""
import ast
import hashlib
import http.client
import logging
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SOURCE = Path('/usr/lib/python3.14/site-packages/tauon/t_modules/t_webserve.py')

class RangeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = SOURCE.read_text()
        tree = ast.parse(source)
        node = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == 'send_file')
        namespace = {'logging': logging}
        exec(compile(ast.Module(body=[node], type_ignores=[]), str(SOURCE), 'exec'), namespace)
        cls.tmp = tempfile.TemporaryDirectory()
        cls.payload = bytes(range(256)) * 1000
        path = Path(cls.tmp.name) / 'sample.flac'
        path.write_bytes(cls.payload)
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self): namespace['send_file'](str(path), 'audio/flac', self)
            def log_message(self, *_): pass
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        print('Tauon source SHA256:', hashlib.sha256(source.encode()).hexdigest())

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close(); cls.thread.join(); cls.tmp.cleanup()

    def request(self, value=None):
        c = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        c.request('GET', '/', headers={'Range': value} if value else {})
        r = c.getresponse(); result = r.status, dict(r.getheaders()), r.read(); c.close()
        return result

    def test_full(self):
        code, h, body = self.request()
        self.assertEqual((code, body), (200, self.payload))
        self.assertEqual(h['Content-Type'], 'audio/flac')
        self.assertEqual(int(h['Content-Length']), len(body))

    def test_bounded_open_suffix_and_clamped(self):
        n = len(self.payload)
        for value, start, end in [('bytes=0-15', 0, 15), ('bytes=65536-', 65536, n-1),
                                  ('bytes=-16', n-16, n-1), ('bytes=255990-999999', 255990, n-1)]:
            with self.subTest(value=value):
                code, h, body = self.request(value)
                self.assertEqual(code, 206)
                self.assertEqual(h['Content-Range'], f'bytes {start}-{end}/{n}')
                self.assertEqual(int(h['Content-Length']), end-start+1)
                self.assertEqual(body, self.payload[start:end+1])

    def test_unsatisfiable(self):
        code, h, body = self.request(f'bytes={len(self.payload)}-')
        self.assertEqual(code, 416)
        self.assertEqual(h['Content-Range'], f'bytes */{len(self.payload)}')
        self.assertEqual(body, b'')

    def test_invalid_and_multiple_fall_back_to_full(self):
        for value in ['bytes=foo-bar', 'bytes=0-1,4-5', 'bytes=-0', 'items=0-1']:
            self.assertEqual(self.request(value)[::2], (200, self.payload))

if __name__ == '__main__': unittest.main(verbosity=2)
