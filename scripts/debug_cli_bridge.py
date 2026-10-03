#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""调试 CLI 的 HTTP 桥 / 中继，供「公网远程调试」用。

两种工作模式，自动择一：

1. **中继模式（推荐，默认优先）**——手机**出站**连上来，因此天然穿透 CGNAT/蜂窝/异网：
   ```
   手机（出站）                        本桥（公网可达，如 Cloudflare Tunnel）      远端调试者
   POST /attach  X-Token: <令牌>  ──►  建立会话 sid                        ──►
   GET  /pull?sid=…&timeout=25     ◄── 长轮询取一条待执行命令（无则超时）
        在手机上执行该命令（本地 CLI）
   POST /result?sid=…&rid=…        ──►  把输出交回                    ──►  POST /  X-Token: <令牌>
                                                                           body = 一条 CLI 命令
                                    ◄──                                ←──  响应 = 命令输出
   ```
2. **回拨模式（兼容旧逻辑）**——桥主动去连手机 CLI（适用于手机与桥同网段 / 走 adb forward）。

安全：两种模式都要求 `X-Token`；手机 attach 时也要带令牌，否则拒绝。令牌用常量时间比较。
"""

import argparse
import hmac
import json
import socket
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

END_MARKER = "--END--"


# ─────────────────────────── 回拨模式工具 ───────────────────────────

def run_cli_command(target_host: str, target_port: int, command: str, token: str, timeout: float = 120.0) -> str:
    """连一次 CLI、鉴权、发命令。

    两个实测要点：
      1. `auth` 与业务命令要**一次写完**再去读——分两次写时，服务端在 auth 响应后就会关连接。
      2. 响应有**三段**：欢迎语 + `auth` 结果 + 命令结果，每段以 `--END--` 结尾；
         读到第 3 个（有令牌）或第 2 个（无令牌）标记为止。
    """
    with socket.create_connection((target_host, target_port), timeout=10) as s:
        s.settimeout(timeout)
        payload = ""
        if token:
            payload += f"auth {token}\n"
        payload += command.rstrip("\n") + "\n"
        s.sendall(payload.encode())

        want = 3 if token else 2
        buf = b""
        try:
            while buf.count(END_MARKER.encode()) < want:
                chunk = s.recv(4096)
                if not chunk:
                    break
                buf += chunk
        except socket.timeout:
            pass
        text = buf.decode(errors="replace")
        parts = text.split(END_MARKER)
        body = END_MARKER.join(parts[1:]) if len(parts) >= 2 else text
        return "\n".join(l for l in body.splitlines() if l.strip())


# ─────────────────────────── 中继模式状态 ───────────────────────────

class RelayState:
    """手机 attach 后的会话状态：待执行命令队列 + 结果表。"""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.sessions: dict[str, dict] = {}

    def _prune_locked(self, max_idle: float = 60.0) -> None:
        now = time.time()
        stale = [sid for sid, v in self.sessions.items() if now - v.get("last_active", v["created"]) > max_idle]
        for sid in stale:
            self.sessions.pop(sid, None)

    def attach(self) -> str:
        sid = uuid.uuid4().hex
        now = time.time()
        with self.lock:
            self._prune_locked()
            self.sessions[sid] = {
                "pending": [],          # [(rid, command)]
                "results": {},          # rid -> output
                "waiters": {},          # rid -> [threading.Event]
                "created": now,
                "last_active": now,
            }
        return sid

    def has_session(self) -> bool:
        with self.lock:
            self._prune_locked(max_idle=45.0)
            return bool(self.sessions)

    def enqueue(self, command: str) -> tuple[str, str] | None:
        """选择最近还在活跃拉取 (last_active 最大) 的会话排一条命令；返回 (sid, rid)，没有会话则 None。"""
        with self.lock:
            self._prune_locked(max_idle=45.0)
            if not self.sessions:
                return None
            # 按最近活跃时间降序排序，选最活跃的那个有效 session
            sid, sess = max(self.sessions.items(), key=lambda kv: kv[1].get("last_active", kv[1]["created"]))
            rid = uuid.uuid4().hex
            sess["pending"].append((rid, command))
            sess["waiters"][rid] = threading.Event()
            return sid, rid

    def pull(self, sid: str, timeout: float) -> tuple[str, str] | None:
        """手机侧长轮询取一条命令。"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            with self.lock:
                sess = self.sessions.get(sid)
                if sess is None:
                    return None
                sess["last_active"] = time.time()
                if sess["pending"]:
                    return sess["pending"].pop(0)
            time.sleep(0.2)
        with self.lock:
            sess = self.sessions.get(sid)
            if sess is not None:
                sess["last_active"] = time.time()
        return None

    def deliver(self, sid: str, rid: str, output: str) -> bool:
        with self.lock:
            sess = self.sessions.get(sid)
            if sess is None:
                return False
            sess["results"][rid] = output
            sess["last_active"] = time.time()
            waiter = sess["waiters"].pop(rid, None)
        if waiter:
            waiter.set()
        return True

    def wait_result(self, sid: str, rid: str, timeout: float) -> str | None:
        with self.lock:
            sess = self.sessions.get(sid)
            if sess is None:
                return None
            waiter = sess["waiters"].get(rid)
        if waiter is None:
            with self.lock:
                return self.sessions.get(sid, {}).get("results", {}).pop(rid, None)
        if not waiter.wait(timeout):
            return None
        with self.lock:
            sess = self.sessions.get(sid)
            return sess["results"].pop(rid, None) if sess else None


RELAY = RelayState()


# ─────────────────────────── HTTP 处理 ───────────────────────────

class Handler(BaseHTTPRequestHandler):
    bridge_token: str = ""
    target: tuple[str, int] = ("127.0.0.1", 7777)
    mode: str = "auto"          # auto | relay | dial
    server_version = "MaaendDebugCliBridge"

    def _auth_ok(self) -> bool:
        return hmac.compare_digest(self.headers.get("X-Token", ""), self.bridge_token)

    def _reply(self, code: int, text: str) -> None:
        body = text.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0]
        if path == "/pull":
            if not self._auth_ok():
                self._reply(401, "unauthorized\n")
                return
            from urllib.parse import parse_qs
            q = parse_qs(self.path.split("?", 1)[1] if "?" in self.path else "")
            sid = (q.get("sid") or [""])[0]
            timeout = float((q.get("timeout") or ["25"])[0])
            got = RELAY.pull(sid, min(max(timeout, 1.0), 60.0))
            if got is None:
                self._reply(204, "")
            else:
                rid, command = got
                # 纯文本两段，第一行 rid、其余是命令：手机上用 curl+sed 就能解析，不必啃 JSON
                self._reply(200, rid + "\n" + command)
            return
        self._reply(200, "Maaend debug CLI bridge/relay.\n")

    def do_POST(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0]
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8", errors="replace") if length else ""

        if path == "/attach":
            if not self._auth_ok():
                self._reply(401, "unauthorized\n")
                return
            sid = RELAY.attach()
            self._reply(200, sid + "\n")
            return

        if path == "/result":
            if not self._auth_ok():
                self._reply(401, "unauthorized\n")
                return
            from urllib.parse import parse_qs
            q = parse_qs(self.path.split("?", 1)[1] if "?" in self.path else "")
            sid = (q.get("sid") or [""])[0]
            rid = (q.get("rid") or [""])[0]
            ok = RELAY.deliver(sid, rid, raw)
            self._reply(200 if ok else 404, "ok\n" if ok else "no such session\n")
            return

        if path == "/" or path == "/cli":
            if not self._auth_ok():
                self._reply(401, "unauthorized: 缺少或错误的 X-Token\n")
                return
            if not raw.strip():
                self._reply(400, "empty command\n")
                return
            use_relay = self.mode == "relay" or (self.mode == "auto" and RELAY.has_session())
            if use_relay:
                queued = RELAY.enqueue(raw)
                if queued is None:
                    self._reply(503, "relay: no device attached\n")
                    return
                sid, rid = queued
                out = RELAY.wait_result(sid, rid, 120.0)
                if out is None:
                    self._reply(504, "relay: device did not answer in time\n")
                else:
                    self._reply(200, out + "\n")
                return
            try:
                out = run_cli_command(self.target[0], self.target[1], raw, self.bridge_token)
                self._reply(200, out + "\n")
            except Exception as exc:  # noqa: BLE001
                self._reply(502, f"bridge error: {type(exc).__name__}: {exc}\n")
            return

        self._reply(404, "not found\n")

    def log_message(self, format: str, *args) -> None:  # noqa: A002
        sys.stderr.write("[bridge] " + (format % args) + "\n")


def main() -> int:
    ap = argparse.ArgumentParser(description="Maaend 调试 CLI 的 HTTP 桥/中继")
    ap.add_argument("--listen", default="127.0.0.1:7788", help="监听地址（默认 127.0.0.1:7788）")
    ap.add_argument("--target", default="127.0.0.1:7777", help="回拨模式下的 CLI 地址 host:port")
    ap.add_argument("--token", required=True, help="App 设置页显示的访问令牌")
    ap.add_argument("--mode", default="auto", choices=["auto", "relay", "dial"], help="工作模式")
    args = ap.parse_args()

    lhost, lport = args.listen.rsplit(":", 1)
    thost, tport = args.target.rsplit(":", 1)
    Handler.bridge_token = args.token
    Handler.target = (thost, int(tport))
    Handler.mode = args.mode

    srv = ThreadingHTTPServer((lhost, int(lport)), Handler)
    print(json.dumps({
        "listen": f"{lhost}:{lport}",
        "dial_target": f"{thost}:{tport}",
        "mode": args.mode,
        "relay_api": {
            "attach": "POST /attach  (X-Token)",
            "pull": "GET /pull?sid=..&timeout=25  (X-Token)",
            "result": "POST /result?sid=..&rid=..  (X-Token)",
            "command": "POST /  (X-Token, body=命令)",
        },
    }, ensure_ascii=False), flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
