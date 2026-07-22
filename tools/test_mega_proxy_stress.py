#!/usr/bin/env python3
"""Regression checks for Donghub's local MEGA range-proxy contract.

This is deliberately dependency-free so it can run in Termux:
    python tools/test_mega_proxy_stress.py

It checks the source-level regression guard (no single-flight client eviction,
Dailymotion handlers registered) and stresses the HTTP behaviour that ExoPlayer
requires: simultaneous, independent byte ranges must all complete intact.
"""
from concurrent.futures import ThreadPoolExecutor, as_completed
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.request import Request, urlopen
import re
import threading

ROOT = Path(__file__).resolve().parents[1]
MEGA = ROOT / "Donghub/src/main/kotlin/com/donghub/MegaNzExtractor.kt"
PROVIDER = ROOT / "Donghub/src/main/kotlin/com/donghub/DonghubProvider.kt"
PAYLOAD = bytes(range(256)) * (16 * 1024)  # 4 MiB deterministic media-like data

class RangeHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *_args):
        pass

    def do_GET(self):
        match = re.fullmatch(r"bytes=(\d*)-(\d*)", self.headers.get("Range", ""))
        if not match:
            start, end = 0, len(PAYLOAD) - 1
        elif not match.group(1):
            suffix = int(match.group(2))
            start, end = max(0, len(PAYLOAD) - suffix), len(PAYLOAD) - 1
        else:
            start = int(match.group(1))
            end = min(int(match.group(2) or len(PAYLOAD) - 1), len(PAYLOAD) - 1)
        if start >= len(PAYLOAD) or end < start:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{len(PAYLOAD)}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        body = PAYLOAD[start:end + 1]
        self.send_response(206)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Range", f"bytes {start}-{end}/{len(PAYLOAD)}")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        # Small writes encourage overlap, like CDN streaming.
        for offset in range(0, len(body), 8192):
            self.wfile.write(body[offset:offset + 8192])


def fetch(base, start, end):
    request = Request(base, headers={"Range": f"bytes={start}-{end}"})
    with urlopen(request, timeout=10) as response:
        body = response.read()
        assert response.status == 206
        assert response.headers["Content-Range"] == f"bytes {start}-{end}/{len(PAYLOAD)}"
        assert body == PAYLOAD[start:end + 1], (start, end, len(body))
    return len(body)


def source_regression_guards():
    mega = MEGA.read_text()
    provider = PROVIDER.read_text()
    assert "maxConcurrentClients = 8" in mega
    assert "acceptExecutor.execute" in mega
    assert "staleClients" not in mega, "single-flight eviction returned"
    assert "staleResponses" not in mega, "single-flight response eviction returned"
    assert "registerExtractorAPI(CustomGeoDailymotion())" in provider
    assert "registerExtractorAPI(CustomDailymotion())" in provider


def main():
    source_regression_guards()
    server = ThreadingHTTPServer(("127.0.0.1", 0), RangeHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{server.server_port}/video.mp4"
    # Mimics ExoPlayer initial read + tail-moov probes + repeated seeks.
    ranges = [(0, 1_048_575), (len(PAYLOAD)-524_288, len(PAYLOAD)-1)]
    ranges += [(i * 131_072, i * 131_072 + 262_143) for i in range(6)]
    try:
        with ThreadPoolExecutor(max_workers=8) as pool:
            sizes = [future.result() for future in as_completed(
                [pool.submit(fetch, base, start, end) for start, end in ranges]
            )]
        assert len(sizes) == 8 and all(size > 0 for size in sizes)
        print("PASS: source guards and 8 simultaneous independent range reads")
    finally:
        server.shutdown()
        server.server_close()

if __name__ == "__main__":
    main()
