"""Internal HTTP adapter. Each solve runs in an isolated, time-limited process."""

from __future__ import annotations

import argparse
import json
import logging
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .schema import InputError, read_input
from . import rules

ROOT = Path(__file__).resolve().parents[1]
LOG = logging.getLogger(__name__)


class SolverServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(
        self,
        address,
        *,
        engine="cpp",
        timeout=840,
        max_bytes=64 * 1024 * 1024,
        workers=1,
    ):
        if engine not in ("python", "cpp", "auto"):
            raise ValueError("Invalid solver engine")
        if timeout <= 0 or max_bytes <= 0 or workers <= 0:
            raise ValueError("Limits must be positive")
        if engine == "cpp":
            from .core_bridge import available

            if not available():
                raise RuntimeError(
                    "Build the current kernel: python -m heatrouting.core.build"
                )
        self.engine, self.solve_timeout, self.max_bytes = engine, timeout, max_bytes
        self.slots = threading.BoundedSemaphore(workers)
        super().__init__(address, Handler)


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(30)

    def reply(self, status, value):
        payload = json.dumps(value, ensure_ascii=False, allow_nan=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(payload)
        self.close_connection = True

    def do_GET(self):
        if self.path != "/health":
            self.reply(404, {"error": "Not found"})
            return
        self.reply(
            200,
            {
                "status": "ok",
                "engine": self.server.engine,
                "spec_version": rules.SPEC_VERSION,
            },
        )

    def do_POST(self):
        if self.path != "/solve":
            self.reply(404, {"error": "Not found"})
            return
        if self.headers.get("Transfer-Encoding"):
            self.reply(400, {"error": "Send a Content-Length request"})
            return
        try:
            length = int(self.headers.get("Content-Length", "-1"))
        except ValueError:
            length = -1
        if length < 0:
            self.reply(411, {"error": "Content-Length required"})
            return
        if length > self.server.max_bytes:
            self.reply(
                413, {"error": "Input exceeds the configured solver request limit"}
            )
            return
        if not self.server.slots.acquire(blocking=False):
            self.reply(
                503, {"error": "Solver is busy; retry after the current calculation"}
            )
            return
        try:
            body = self.rfile.read(length)
            if len(body) != length:
                raise InputError("Incomplete request body")
            data = read_input(json.loads(body))
            with tempfile.TemporaryDirectory(prefix="heat-routing-") as folder:
                work = Path(folder)
                inp, out = work / "input.geojson", work / "result"
                inp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
                del data, body
                with (work / "solver.log").open("wb") as log:
                    process = subprocess.run(
                        [
                            sys.executable,
                            str(ROOT / "run_sample.py"),
                            str(inp),
                            "--out",
                            str(out),
                            "--engine",
                            self.server.engine,
                            "--no-frames",
                            "--no-figures",
                        ],
                        cwd=ROOT,
                        stdout=log,
                        stderr=subprocess.STDOUT,
                        timeout=self.server.solve_timeout,
                        check=False,
                    )
                if process.returncode:
                    LOG.error(
                        "Solver failed: %s",
                        (work / "solver.log").read_text(errors="replace")[-8000:],
                    )
                    self.reply(
                        500,
                        {"error": "Solver failed; no validated result was produced"},
                    )
                    return
                # The CLI publishes only independently validated GeoJSON.
                self.reply(
                    200,
                    json.loads((out / "result.geojson").read_text(encoding="utf-8")),
                )
        except (InputError, ValueError, UnicodeError) as exc:
            self.reply(422, {"error": str(exc)})
        except subprocess.TimeoutExpired:
            self.reply(504, {"error": "Solver calculation timed out"})
        except TimeoutError:
            self.reply(408, {"error": "Request upload timed out"})
        except (BrokenPipeError, ConnectionResetError):
            LOG.info("Client disconnected")
        except Exception:
            LOG.exception("Solver adapter failure")
            self.reply(500, {"error": "Internal solver error"})
        finally:
            self.server.slots.release()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default=os.getenv("SOLVER_HOST", "127.0.0.1"))
    parser.add_argument(
        "--port", type=int, default=int(os.getenv("SOLVER_PORT", "8090"))
    )
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    with SolverServer(
        (args.host, args.port),
        engine=os.getenv("SOLVER_ENGINE", "cpp"),
        timeout=float(os.getenv("SOLVER_TIMEOUT_SECONDS", "840")),
        max_bytes=int(os.getenv("SOLVER_MAX_REQUEST_BYTES", str(64 * 1024 * 1024))),
        workers=int(os.getenv("SOLVER_WORKERS", "1")),
    ) as server:
        LOG.info(
            "Solver listening on %s:%s (%s)", *server.server_address, server.engine
        )
        server.serve_forever()


if __name__ == "__main__":
    main()
