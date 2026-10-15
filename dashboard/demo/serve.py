"""Serves a built dashboard for the demo recorder, taking data files the build does not have
from the published site, so that a recording shows the current code with real data.

    python3 demo/serve.py dist 4173 https://sieveaml.org/
"""
import http.server
import os
import sys
import urllib.request

root, port, live = sys.argv[1], int(sys.argv[2]), sys.argv[3].rstrip('/') + '/'


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=root, **k)

    def do_GET(self):
        path = self.path.split('?', 1)[0]
        if path.startswith('/data/') and not os.path.exists(self.translate_path(path)):
            try:
                with urllib.request.urlopen(live + path.lstrip('/'), timeout=60) as r:
                    body = r.read()
            except Exception as e:  # noqa: BLE001 -- reported to the browser as a missing file
                self.send_error(404, str(e))
                return
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        super().do_GET()


http.server.ThreadingHTTPServer(('127.0.0.1', port), Handler).serve_forever()
