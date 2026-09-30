#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""调试 CLI 的 HTTP 桥：把裸 TCP 行协议包成 HTTP，便于经 Cloudflare Tunnel 暴露给远端调试者。

## 为什么需要它

手机上的调试 CLI 是**裸 TCP 行协议**（一行一命令，响应以 `--END--` 结尾；服务端先发欢迎语
+`--END--`，再发响应+`--END--`）。而 Cloudflare Tunnel 的免费套餐只暴露 **HTTP(S)** 服务，
裸 TCP 进不来（要裸 TCP 得让远端装 cloudflared + 配 Cloudflare Access）。
所以在本机跑这个薄桥：HTTP 进 → 裸 TCP 出，并把**令牌鉴权**放在桥上（同时也透传给 App 侧，
App 在远程调试模式下自己也会再校验一次——两道闸门）。

## 拓扑

    远端调试者
      │  HTTPS  POST https://maaendset.qiisme1021.space/   （头 X-Token: <访问令牌>）
      ▼
    Cloudflare Tunnel（本机 cloudflared，ingress: maaendset.* → http://127.0.0.1:7788）
      ▼
    本脚本（默认只听 127.0.0.1:7788）
      │  裸 TCP  先发 `auth <令牌>` 再发命令
      ▼
    手机 App 的 CLI（远程调试开启后监听 0.0.0.0:7777；局域网来源必须鉴权）
      ▲
      └── 也可以走 adb forward（127.0.0.1:7777，回环免令牌）——调试本机时用

## 用法

    # 局域网直连手机（推荐：这样 App 侧也会校验令牌）
    python3 scripts/debug_cli_bridge.py --target 192.168.100.196:7777 --token <访问令牌>

    # 或者走 adb forward（回环，App 侧免令牌；桥这层仍校验）
    python3 scripts/debug_cli_bridge.py --target 127.0.0.1:7777 --token <访问令牌>

    # 远端：
    curl -s -X POST https://maaendset.qiisme1021.space/ \
         -H 'X-Token: <访问令牌>' --data 'status'

## 安全

  - 令牌比较用 `hmac.compare_digest`（常量时间）。
  - 只绑回环（`--listen` 默认 127.0.0.1）；要暴露请交给 Cloudflare Tunnel，别直接开公网。
  - 建议再在 Cloudflare 侧给该 hostname 配 Access 策略（邮箱验证码），做第二道闸门。
  - 不用时把 App 里的「远程调试」关掉：手机侧就完全不监听局域网了。
"""

import argparse
import hmac
import json
import socket
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

END_MARKER = "--END--"


def run_cli_command(target_host: str, target_port: int, command: str, token: str, timeout: float = 120.0) -> str:
    """连一次 CLI、鉴权、发命令。

    注意两点（都是实测踩出来的）：
      1. `auth` 与业务命令要**一次写完**再去读——分两次写时，服务端在 auth 响应后就会关连接
         （表现为 BrokenPipeError / 空响应）。
      2. 响应有**三段**：欢迎语 + `auth` 结果（若有）+ 命令结果，每段都以 `--END--` 结尾；
         所以要读到第 2 个（无令牌）或第 3 个（有令牌）标记为止。
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
        # 去掉欢迎语那一段，只留后续（auth 结果 + 命令结果）
        text = buf.decode(errors="replace")
        parts = text.split(END_MARKER)
        body = END_MARKER.join(parts[1:]) if len(parts) >= 2 else text
        return "\n".join(l for l in body.splitlines() if l.strip())


class Handler(BaseHTTPRequestHandler):
    bridge_token: str = ""
    target: tuple[str, int] = ("127.0.0.1", 7777)
    server_version = "MaaendDebugCliBridge"

    def _reply(self, code: int, text: str) -> None:
        body = text.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802 (BaseHTTPRequestHandler 的命名)
        self._reply(200, "Maaend debug CLI bridge. POST a command with header X-Token.\n")

    def do_POST(self) -> None:  # noqa: N802
        supplied = self.headers.get("X-Token", "")
        if not hmac.compare_digest(supplied, self.bridge_token):
            self._reply(401, "unauthorized: 缺少或错误的 X-Token\n")
            return
        length = int(self.headers.get("Content-Length") or 0)
        command = self.rfile.read(length).decode("utf-8", errors="replace") if length else ""
        if not command.strip():
            self._reply(400, "empty command\n")
            return
        try:
            out = run_cli_command(self.target[0], self.target[1], command, self.bridge_token)
            self._reply(200, out + "\n")
        except Exception as exc:  # noqa: BLE001 - 桥要把任何失败如实回报给远端
            self._reply(502, f"bridge error: {type(exc).__name__}: {exc}\n")

    def log_message(self, format: str, *args) -> None:  # noqa: A002 - 必须与基类同名
        sys.stderr.write("[bridge] " + (format % args) + "\n")


def main() -> int:
    ap = argparse.ArgumentParser(description="Maaend 调试 CLI 的 HTTP 桥")
    ap.add_argument("--listen", default="127.0.0.1:7788", help="监听地址（默认 127.0.0.1:7788）")
    ap.add_argument("--target", default="127.0.0.1:7777", help="CLI 地址 host:port")
    ap.add_argument("--token", required=True, help="App 设置页显示的访问令牌")
    args = ap.parse_args()

    lhost, lport = args.listen.rsplit(":", 1)
    thost, tport = args.target.rsplit(":", 1)
    Handler.bridge_token = args.token
    Handler.target = (thost, int(tport))

    srv = ThreadingHTTPServer((lhost, int(lport)), Handler)
    print(json.dumps({
        "listen": f"{lhost}:{lport}",
        "target": f"{thost}:{tport}",
        "hint": f"curl -s -X POST http://{lhost}:{lport}/ -H 'X-Token: <token>' --data 'status'",
    }, ensure_ascii=False), flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
