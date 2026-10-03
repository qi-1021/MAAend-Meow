#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PC 端远程调试运维客户端。

封装与 debug_cli_bridge 的 HTTP 通信，提供开箱即用的高阶交互：
  - 交互点击/滑动/按键 (click, swipe, key)
  - 远程文件下载拉取 (pull / pullfile)
  - 远程文件/配置/补丁热推 (push / pushfile)
  - 远程目录浏览 (ls, rm)
  - 终末地游戏进程启停 (game launch, kill, top)
  - 手机硬件与环境状态快查 (device-status)
  - 一键远程静默安装 APK (update-apk)
"""

import argparse
import base64
import os
import sys
import urllib.error
import urllib.request


def send_raw_command(bridge_url: str, token: str, command: str, timeout: float = 120.0) -> str:
    url = bridge_url.rstrip("/") + "/"
    req = urllib.request.Request(
        url,
        data=command.encode("utf-8"),
        headers={
            "X-Token": token,
            "Content-Type": "text/plain; charset=utf-8",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            text = resp.read().decode("utf-8", errors="replace")
            # 去掉结尾 --END-- 标记
            lines = [l for l in text.splitlines() if l.strip() != "--END--"]
            return "\n".join(lines)
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {e.code}: {body}") from e
    except Exception as e:
        raise RuntimeError(f"Bridge communication error: {e}") from e


def cmd_pullfile(bridge_url: str, token: str, remote_path: str, local_path: str, chunk_size: int = 512 * 1024):
    print(f"[*] Pulling remote '{remote_path}' -> '{local_path}' ...")
    offset = 0
    total_bytes = None
    with open(local_path, "wb") as f:
        while True:
            resp = send_raw_command(bridge_url, token, f"pullfile {remote_path} {offset} {chunk_size}")
            lines = resp.splitlines()
            if not lines or lines[0].startswith("error:"):
                raise RuntimeError(resp)
            # ok: offset=0 total=12345 chunk=1024 eof=false
            status_line = lines[0]
            parts = dict(p.split("=") for p in status_line.replace("ok: ", "").split() if "=" in p)
            if total_bytes is None:
                total_bytes = int(parts.get("total", 0))
            chunk_len = int(parts.get("chunk", 0))
            eof = parts.get("eof", "false").lower() == "true"
            b64_line = ""
            for l in lines[1:]:
                if l.startswith("b64:"):
                    b64_line = l[4:]
                    break
            if b64_line:
                data = base64.b64decode(b64_line)
                f.write(data)
                offset += len(data)
            print(f"\r    {offset} / {total_bytes} bytes ({(offset / max(1, total_bytes)) * 100:.1f}%)", end="", flush=True)
            if eof or chunk_len == 0:
                break
    print(f"\n[+] File successfully saved to {local_path} ({offset} bytes)")


def cmd_pushfile(bridge_url: str, token: str, local_path: str, remote_path: str, chunk_size: int = 256 * 1024):
    if not os.path.isfile(local_path):
        raise FileNotFoundError(f"Local file not found: {local_path}")
    total_bytes = os.path.getsize(local_path)
    print(f"[*] Pushing local '{local_path}' -> '{remote_path}' ({total_bytes} bytes)...")
    offset = 0
    with open(local_path, "rb") as f:
        first = True
        while True:
            chunk = f.read(chunk_size)
            if not chunk:
                break
            b64_str = base64.b64encode(chunk).decode("ascii")
            append_flag = "false" if first else "true"
            resp = send_raw_command(bridge_url, token, f"pushfile {remote_path} {b64_str} {append_flag}")
            if resp.startswith("error:"):
                raise RuntimeError(resp)
            offset += len(chunk)
            first = False
            print(f"\r    {offset} / {total_bytes} bytes ({(offset / max(1, total_bytes)) * 100:.1f}%)", end="", flush=True)
    print(f"\n[+] File successfully pushed to {remote_path}")


def main():
    parser = argparse.ArgumentParser(description="Maaend 远程调试运维客户端")
    parser.add_argument("--url", default="http://127.0.0.1:7788", help="Bridge HTTP URL")
    parser.add_argument("--token", default="v2SZGz5BnGajejsR32cp2QBsK9NlnTDh", help="Auth token")
    subparsers = parser.add_subparsers(dest="subcommand", help="子命令")

    # raw command
    p_cmd = subparsers.add_parser("cmd", help="执行任意原生 CLI 命令")
    p_cmd.add_argument("args", nargs=argparse.REMAINDER, help="命令内容")

    # pull
    p_pull = subparsers.add_parser("pull", help="拉取远程设备文件到本地")
    p_pull.add_argument("remote", help="设备绝对路径")
    p_pull.add_argument("local", help="本地目标路径")

    # push
    p_push = subparsers.add_parser("push", help="推送本地文件到远程设备")
    p_push.add_argument("local", help="本地文件路径")
    p_push.add_argument("remote", help="设备绝对路径")

    # screencap
    subparsers.add_parser("screencap", help="立即截屏并保存最新图像")

    # click & swipe
    p_click = subparsers.add_parser("click", help="点击屏幕坐标")
    p_click.add_argument("x", type=int)
    p_click.add_argument("y", type=int)

    p_swipe = subparsers.add_parser("swipe", help="滑动屏幕")
    p_swipe.add_argument("x1", type=int)
    p_swipe.add_argument("y1", type=int)
    p_swipe.add_argument("x2", type=int)
    p_swipe.add_argument("y2", type=int)
    p_swipe.add_argument("duration", type=int, nargs="?", default=300)

    # game
    p_game = subparsers.add_parser("game", help="管理终末地游戏进程")
    p_game.add_argument("action", choices=["launch", "kill", "top"])
    p_game.add_argument("display", type=int, nargs="?", default=None)

    # device
    subparsers.add_parser("device-status", help="手机状态查询")

    args = parser.parse_args()

    if not args.subcommand or args.subcommand == "cmd":
        cmd_str = " ".join(getattr(args, "args", []))
        if not cmd_str:
            print("请输入命令，例如: python3 scripts/remote_ops.py status")
            return
        res = send_raw_command(args.url, args.token, cmd_str)
        print(res)
    elif args.subcommand == "pull":
        cmd_pullfile(args.url, args.token, args.remote, args.local)
    elif args.subcommand == "push":
        cmd_pushfile(args.url, args.token, args.local, args.remote)
    elif args.subcommand == "screencap":
        res = send_raw_command(args.url, args.token, "screencap")
        print(res)
    elif args.subcommand == "click":
        res = send_raw_command(args.url, args.token, f"click {args.x} {args.y}")
        print(res)
    elif args.subcommand == "swipe":
        res = send_raw_command(args.url, args.token, f"swipe {args.x1} {args.y1} {args.x2} {args.y2} {args.duration}")
        print(res)
    elif args.subcommand == "game":
        arg = f" {args.display}" if args.display is not None else ""
        res = send_raw_command(args.url, args.token, f"game {args.action}{arg}")
        print(res)
    elif args.subcommand == "device-status":
        res = send_raw_command(args.url, args.token, "device-status")
        print(res)


if __name__ == "__main__":
    main()
