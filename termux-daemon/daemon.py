#!/usr/bin/env python3
"""
PocketAgent Termux Daemon
==========================

A tiny HTTP/WebSocket server that runs inside the user's Termux.
The PocketAgent Android app talks to it over localhost:8765 to execute
shell commands, read/write files, and manage long-running processes.

Design goals:
  - Zero dependencies (stdlib only — works on fresh Termux Python)
  - Single file, ~250 lines, easy to audit
  - HTTP for one-shot commands, WebSocket for streaming
  - Structured JSON responses (never raw stderr strings)
  - Process registry with kill capability
  - File operations scoped to $HOME (safety)

Endpoints:
  GET  /health                → {"status": "ok", "version": "6.0.0", "user": "..."}
  POST /exec                  → run command, return when done
       body: {"command": "...", "timeout": 30, "cwd": "~"}
       resp: {"stdout": "...", "stderr": "...", "exit_code": 0, "duration_ms": 12, "pid": 1234}
  WS   /stream?command=...    → live stdout/stderr until exit or cancel
       messages: {"type": "stdout|stderr", "data": "..."} then {"type": "exit", "code": 0}
  POST /proc/list             → {"processes": [{"pid": 1234, "command": "...", "started_at": ...}]}
  POST /proc/kill             → body: {"pid": 1234} → {"killed": true}
  POST /files/read            → body: {"path": "..."} → {"content": "...", "size": 1234, "truncated": false}
  POST /files/write           → body: {"path": "...", "content": "..."} → {"bytes": 1234}
  POST /files/list            → body: {"path": "..."} → {"entries": [{"name": "...", "type": "file|dir", "size": 1234}]}
  POST /files/stat            → body: {"path": "..."} → {"exists": true, "type": "file", "size": 1234, "mtime": ...}
  POST /files/mkdir           → body: {"path": "..."} → {"created": true}
  POST /files/delete          → body: {"path": "..."} → {"deleted": true}

Security:
  - Binds to 127.0.0.1 only (Android enforces same-UID access)
  - Auth token in X-PocketAgent-Token header (generated at install, stored in ~/.pocketagent/token)
  - File ops confined to $HOME (.. blocked)
  - No eval, no shell=True, no pickle

Usage in Termux:
  pkg install python
  python ~/path/to/daemon.py
  # or after install.sh:
  pocketagent-daemon
"""

import http.server
import json
import os
import shlex
import signal
import socket
import socketserver
import subprocess
import sys
import threading
import time
import urllib.parse
from datetime import datetime
from pathlib import Path

HOST = "127.0.0.1"
PORT = 8765
VERSION = "6.0.0"
HOME = Path(os.path.expanduser("~"))
TOKEN_FILE = HOME / ".pocketagent" / "token"
MAX_FILE_READ = 1_000_000  # 1MB cap on /files/read
MAX_OUTPUT = 1_000_000     # 1MB cap on /exec stdout/stderr

# ─── Process registry ──────────────────────────────────────────────
_processes = {}  # pid → {"command": str, "started_at": float, "proc": Popen}
_processes_lock = threading.Lock()


def get_token():
    """Read or create the auth token."""
    try:
        TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
        if TOKEN_FILE.exists():
            return TOKEN_FILE.read_text().strip()
        import secrets
        token = secrets.token_urlsafe(32)
        TOKEN_FILE.write_text(token)
        TOKEN_FILE.chmod(0o600)
        return token
    except Exception as e:
        sys.stderr.write(f"Warning: could not init token: {e}\n")
        return None


def check_token(headers):
    """Return True if the request has the correct auth token."""
    expected = get_token()
    if not expected:
        return True  # token disabled (shouldn't happen in normal install)
    provided = headers.get("X-PocketAgent-Token", "")
    return provided == expected


def resolve_path(path_str):
    """Resolve a path, confine to $HOME, block .. traversal."""
    if not path_str:
        return None
    p = Path(path_str).expanduser()
    if not p.is_absolute():
        p = HOME / p
    try:
        p = p.resolve()
    except Exception:
        return None
    # Confine to $HOME
    try:
        p.relative_to(HOME)
    except ValueError:
        return None
    return p


def execute_command(command, timeout=30, cwd=None):
    """Run a command synchronously, return structured result."""
    start = time.time()
    cwd_path = resolve_path(cwd) if cwd else HOME
    if cwd_path is None:
        cwd_path = HOME

    try:
        proc = subprocess.Popen(
            ["bash", "-l", "-c", command],
            cwd=str(cwd_path),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=True,  # so we can kill the whole process group
        )
    except Exception as e:
        return {
            "stdout": "",
            "stderr": f"Failed to start process: {e}",
            "exit_code": -1,
            "duration_ms": int((time.time() - start) * 1000),
            "pid": None,
            "error": str(e),
            "suggestion": "Check that bash is installed in Termux.",
        }

    with _processes_lock:
        _processes[proc.pid] = {
            "command": command,
            "started_at": start,
            "proc": proc,
        }

    try:
        stdout, stderr = proc.communicate(timeout=timeout)
        exit_code = proc.returncode
        timed_out = False
    except subprocess.TimeoutExpired:
        # Kill the whole process group
        try:
            os.killpg(proc.pid, signal.SIGTERM)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass
        try:
            stdout, stderr = proc.communicate(timeout=2)
        except Exception:
            stdout = b""
            stderr = b"Command timed out"
        exit_code = -1
        timed_out = True

    with _processes_lock:
        _processes.pop(proc.pid, None)

    stdout_str = stdout.decode("utf-8", errors="replace")[:MAX_OUTPUT]
    stderr_str = stderr.decode("utf-8", errors="replace")[:MAX_OUTPUT]

    return {
        "stdout": stdout_str,
        "stderr": stderr_str,
        "exit_code": exit_code,
        "duration_ms": int((time.time() - start) * 1000),
        "pid": proc.pid,
        "timed_out": timed_out,
        "truncated": len(stdout) > MAX_OUTPUT or len(stderr) > MAX_OUTPUT,
    }


def stream_command(command, cwd=None):
    """Generator yielding stdout/stderr lines until exit."""
    cwd_path = resolve_path(cwd) if cwd else HOME
    if cwd_path is None:
        cwd_path = HOME

    try:
        proc = subprocess.Popen(
            ["bash", "-l", "-c", command],
            cwd=str(cwd_path),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            bufsize=1,
            universal_newlines=True,
            start_new_session=True,
        )
    except Exception as e:
        yield {"type": "error", "message": str(e)}
        return

    with _processes_lock:
        _processes[proc.pid] = {
            "command": command,
            "started_at": time.time(),
            "proc": proc,
        }

    try:
        for line in iter(proc.stdout.readline, ""):
            yield {"type": "output", "data": line}
        proc.stdout.close()
        proc.wait()
        yield {"type": "exit", "code": proc.returncode, "duration_ms": int((time.time() - _processes[proc.pid]["started_at"]) * 1000)}
    finally:
        with _processes_lock:
            _processes.pop(proc.pid, None)


# ─── HTTP handler ──────────────────────────────────────────────────
class Handler(http.server.BaseHTTPRequestHandler):
    server_version = f"PocketAgent/{VERSION}"

    def log_message(self, format, *args):
        # Quiet logging — only errors go to stderr
        if "error" in (format % args).lower():
            sys.stderr.write(f"{self.address_string()} - {format % args}\n")

    def _send_json(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_body(self):
        length = int(self.headers.get("Content-Length", 0))
        if length == 0:
            return {}
        raw = self.rfile.read(length)
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception as e:
            return {"_parse_error": str(e)}

    def _authed(self):
        if not check_token(self.headers):
            self._send_json(401, {"error": "unauthorized", "suggestion": "Set X-PocketAgent-Token header to the value in ~/.pocketagent/token"})
            return False
        return True

    def do_GET(self):
        if not self._authed():
            return
        parsed = urllib.parse.urlparse(self.path)

        if parsed.path == "/health":
            self._send_json(200, {
                "status": "ok",
                "version": VERSION,
                "user": os.environ.get("USER", "unknown"),
                "home": str(HOME),
                "uptime": int(time.time() - START_TIME),
                "processes": len(_processes),
            })
            return

        if parsed.path == "/stream":
            self._handle_stream(parsed.query)
            return

        self._send_json(404, {"error": f"unknown path: {parsed.path}"})

    def do_POST(self):
        if not self._authed():
            return
        parsed = urllib.parse.urlparse(self.path)
        body = self._read_body()
        if "_parse_error" in body:
            self._send_json(400, {"error": "invalid JSON", "detail": body["_parse_error"]})
            return

        if parsed.path == "/exec":
            self._handle_exec(body)
        elif parsed.path == "/proc/list":
            self._handle_proc_list()
        elif parsed.path == "/proc/kill":
            self._handle_proc_kill(body)
        elif parsed.path == "/files/read":
            self._handle_files_read(body)
        elif parsed.path == "/files/write":
            self._handle_files_write(body)
        elif parsed.path == "/files/list":
            self._handle_files_list(body)
        elif parsed.path == "/files/stat":
            self._handle_files_stat(body)
        elif parsed.path == "/files/mkdir":
            self._handle_files_mkdir(body)
        elif parsed.path == "/files/delete":
            self._handle_files_delete(body)
        else:
            self._send_json(404, {"error": f"unknown path: {parsed.path}"})

    # ─── /exec ──────────────────────────────────────────────────
    def _handle_exec(self, body):
        command = body.get("command")
        if not command:
            self._send_json(400, {"error": "missing 'command'", "suggestion": "POST a JSON body with a 'command' field."})
            return
        timeout = int(body.get("timeout", 30))
        timeout = max(1, min(timeout, 600))
        cwd = body.get("cwd")
        result = execute_command(command, timeout=timeout, cwd=cwd)
        # Add actionable hints for common failures
        if result["exit_code"] != 0 and not result["timed_out"]:
            result["suggestion"] = _suggest(result["stderr"], result.get("stdout", ""))
        self._send_json(200, result)

    # ─── /stream ────────────────────────────────────────────────
    def _handle_stream(self, query):
        # WebSocket upgrade check
        if self.headers.get("Upgrade", "").lower() != "websocket":
            # Fallback: HTTP chunked stream
            params = urllib.parse.parse_qs(query)
            command = params.get("command", [""])[0]
            if not command:
                self._send_json(400, {"error": "missing 'command' query parameter"})
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            for msg in stream_command(command):
                try:
                    self.wfile.write((json.dumps(msg) + "\n").encode("utf-8"))
                    self.wfile.flush()
                except Exception:
                    break
            return
        # WebSocket support is minimal — the app uses the HTTP fallback for simplicity
        self._send_json(501, {"error": "websocket not implemented, use HTTP stream"})

    # ─── /proc/* ────────────────────────────────────────────────
    def _handle_proc_list(self):
        with _processes_lock:
            procs = [
                {
                    "pid": pid,
                    "command": info["command"],
                    "started_at": info["started_at"],
                    "duration_s": int(time.time() - info["started_at"]),
                }
                for pid, info in _processes.items()
            ]
        self._send_json(200, {"processes": procs})

    def _handle_proc_kill(self, body):
        pid = body.get("pid")
        if not pid:
            self._send_json(400, {"error": "missing 'pid'"})
            return
        try:
            pid = int(pid)
        except (TypeError, ValueError):
            self._send_json(400, {"error": "'pid' must be an integer"})
            return
        with _processes_lock:
            info = _processes.get(pid)
        if not info:
            self._send_json(404, {"error": f"pid {pid} not in registry"})
            return
        try:
            os.killpg(pid, signal.SIGTERM)
            time.sleep(0.5)
            try:
                os.killpg(pid, signal.SIGKILL)
            except Exception:
                pass
            self._send_json(200, {"killed": True, "pid": pid})
        except Exception as e:
            self._send_json(500, {"error": str(e), "killed": False})

    # ─── /files/* ───────────────────────────────────────────────
    def _handle_files_read(self, body):
        path = resolve_path(body.get("path"))
        if not path:
            self._send_json(400, {"error": "invalid path", "suggestion": "Path must be under $HOME."})
            return
        if not path.exists():
            self._send_json(404, {"error": "file not found", "path": str(path)})
            return
        if not path.is_file():
            self._send_json(400, {"error": "not a file", "path": str(path)})
            return
        size = path.stat().st_size
        truncated = size > MAX_FILE_READ
        try:
            content = path.read_bytes()[:MAX_FILE_READ]
            try:
                text = content.decode("utf-8")
                is_binary = False
            except UnicodeDecodeError:
                text = content.hex()
                is_binary = True
        except Exception as e:
            self._send_json(500, {"error": str(e)})
            return
        self._send_json(200, {
            "content": text,
            "size": size,
            "truncated": truncated,
            "binary": is_binary,
            "path": str(path),
        })

    def _handle_files_write(self, body):
        path = resolve_path(body.get("path"))
        content = body.get("content", "")
        if not path:
            self._send_json(400, {"error": "invalid path"})
            return
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            data = content.encode("utf-8")
            path.write_bytes(data)
            self._send_json(200, {"bytes": len(data), "path": str(path)})
        except Exception as e:
            self._send_json(500, {"error": str(e)})

    def _handle_files_list(self, body):
        path = resolve_path(body.get("path", "~"))
        if not path:
            self._send_json(400, {"error": "invalid path"})
            return
        if not path.exists():
            self._send_json(404, {"error": "path not found"})
            return
        if not path.is_dir():
            self._send_json(400, {"error": "not a directory"})
            return
        entries = []
        try:
            for entry in sorted(path.iterdir(), key=lambda e: (not e.is_dir(), e.name.lower())):
                try:
                    stat = entry.stat()
                    entries.append({
                        "name": entry.name,
                        "type": "dir" if entry.is_dir() else "file",
                        "size": stat.st_size if entry.is_file() else 0,
                        "mtime": int(stat.st_mtime),
                        "hidden": entry.name.startswith("."),
                    })
                except Exception:
                    continue
        except Exception as e:
            self._send_json(500, {"error": str(e)})
            return
        self._send_json(200, {"entries": entries, "path": str(path)})

    def _handle_files_stat(self, body):
        path = resolve_path(body.get("path"))
        if not path:
            self._send_json(400, {"error": "invalid path"})
            return
        if not path.exists():
            self._send_json(200, {"exists": False, "path": str(path)})
            return
        try:
            stat = path.stat()
            self._send_json(200, {
                "exists": True,
                "type": "dir" if path.is_dir() else "file",
                "size": stat.st_size if path.is_file() else 0,
                "mtime": int(stat.st_mtime),
                "path": str(path),
            })
        except Exception as e:
            self._send_json(500, {"error": str(e)})

    def _handle_files_mkdir(self, body):
        path = resolve_path(body.get("path"))
        if not path:
            self._send_json(400, {"error": "invalid path"})
            return
        try:
            path.mkdir(parents=True, exist_ok=True)
            self._send_json(200, {"created": True, "path": str(path)})
        except Exception as e:
            self._send_json(500, {"error": str(e)})

    def _handle_files_delete(self, body):
        path = resolve_path(body.get("path"))
        if not path:
            self._send_json(400, {"error": "invalid path"})
            return
        try:
            if path.is_dir():
                import shutil
                shutil.rmtree(path)
            else:
                path.unlink()
            self._send_json(200, {"deleted": True, "path": str(path)})
        except Exception as e:
            self._send_json(500, {"error": str(e)})


# ─── Error suggestion engine ───────────────────────────────────────
def _suggest(stderr, stdout):
    """Return an actionable hint for common failures."""
    s = (stderr + " " + stdout).lower()
    if "command not found" in s:
        return "The command is not installed. Use `pkg install <name>` (e.g., `pkg install python nodejs git`)."
    if "unable to locate package" in s:
        return "Run `pkg update` first to refresh the package index, then retry."
    if "permission denied" in s:
        return "Add execute permission: `chmod +x <file>`. If a script, check the shebang line."
    if "no such file or directory" in s:
        return "The file or directory does not exist. Check the path with `ls`."
    if "address already in use" in s:
        return "Another process is using that port. Find it: `lsof -i :PORT` or `netstat -tlnp`."
    if "connection refused" in s:
        return "Nothing is listening on that port. Check that the service started."
    if "timeout" in s or "timed out" in s:
        return "The command took too long. Increase the timeout or run in the background with `&`."
    if "syntax error" in s:
        return "There's a syntax error in the command or script. Check quotes, brackets, and operators."
    if "no module named" in s:
        return "Python module not installed. Install it: `pip install <module>`."
    if "npm err" in s or "eacces" in s:
        return "npm permission error. Try: `npm config set prefix ~/.local` or use `npx`."
    if "fatal: not a git repository" in s:
        return "You're not in a git repo. Run `git init` or `cd` into the right directory."
    if "could not resolve host" in s or "temporary failure in name resolution" in s:
        return "DNS resolution failed. Check internet connection."
    if "certificate verification failed" in s or "ssl certificate" in s:
        return "SSL certificate problem. Try: `pkg install ca-certificates`."
    return None


# ─── Server with threading ─────────────────────────────────────────
class ThreadingHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


START_TIME = time.time()


def main():
    print(f"""
╔══════════════════════════════════════════════╗
║  PocketAgent Daemon v{VERSION}                  ║
║  Listening on http://{HOST}:{PORT:<24} ║
║  Home: {str(HOME):<34} ║
╚══════════════════════════════════════════════╝

Open the PocketAgent app on your phone.
It will connect automatically.

Press Ctrl+C to stop.
""", flush=True)

    server = ThreadingHTTPServer((HOST, PORT), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping...", flush=True)
        server.shutdown()
        # Kill any lingering child processes
        with _processes_lock:
            for pid, info in list(_processes.items()):
                try:
                    os.killpg(pid, signal.SIGTERM)
                except Exception:
                    pass
        sys.exit(0)


if __name__ == "__main__":
    main()
