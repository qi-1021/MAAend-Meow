#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Check and synchronize upstream MaaEnd and MaaFramework updates.
Updates submodule pointer, UPSTREAM_VERSIONS.json, and README.md.
Ensures zero pollution of system /tmp.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
MAAEND_ROOT = PROJECT_ROOT / "upstream" / "maaend"
VERSIONS_FILE = PROJECT_ROOT / "UPSTREAM_VERSIONS.json"
README_FILE = PROJECT_ROOT / "README.md"
TMP_DIR = PROJECT_ROOT / ".tmp"

TMP_DIR.mkdir(parents=True, exist_ok=True)
os.environ["TMPDIR"] = str(TMP_DIR)
os.environ["TEMP"] = str(TMP_DIR)
os.environ["TMP"] = str(TMP_DIR)


def log(msg: str):
    print(f"[Auto-Sync] {msg}", flush=True)


def run_cmd(args: list[str], cwd: Path | None = None) -> str:
    res = subprocess.run(
        args, cwd=cwd or PROJECT_ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True
    )
    return res.stdout.strip()


def check_maaend_update() -> tuple[bool, str, str, str]:
    """Check if upstream MaaEnd has a new commit on v2 branch."""
    log("Checking upstream MaaEnd updates...")
    run_cmd(["git", "fetch", "origin", "v2"], cwd=MAAEND_ROOT)
    current_commit = run_cmd(["git", "rev-parse", "HEAD"], cwd=MAAEND_ROOT)
    latest_commit = run_cmd(["git", "rev-parse", "origin/v2"], cwd=MAAEND_ROOT)
    latest_subject = run_cmd(["git", "log", "-1", "--format=%s", "origin/v2"], cwd=MAAEND_ROOT)

    if current_commit != latest_commit:
        log(f"New MaaEnd commit detected: {current_commit[:7]} -> {latest_commit[:7]}")
        log(f"Latest commit message: {latest_subject}")
        return True, current_commit, latest_commit, latest_subject
    else:
        log(f"MaaEnd is already up-to-date ({current_commit[:7]}).")
        return False, current_commit, latest_commit, latest_subject


def check_maafw_update(current_release: str) -> tuple[bool, str]:
    """Check if MaaFramework has a newer stable release tag."""
    log("Checking MaaFramework latest release...")
    url = "https://api.github.com/repos/MaaXYZ/MaaFramework/releases/latest"
    req = urllib.request.Request(url, headers={"User-Agent": "MAAend-Android-Sync"})
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"token {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            latest_tag = data.get("tag_name", "").strip()
            if latest_tag and latest_tag != current_release:
                log(f"New MaaFramework release detected: {current_release} -> {latest_tag}")
                return True, latest_tag
            return False, current_release
    except Exception as e:
        log(f"Warning: Failed to fetch latest MaaFramework release: {e}")
        return False, current_release


def update_maaend_submodule(target_commit: str):
    log(f"Updating MaaEnd submodule to {target_commit[:7]}...")
    run_cmd(["git", "checkout", target_commit], cwd=MAAEND_ROOT)
    import prepare_maaend
    prepare_maaend.main()


def update_records(new_maaend_commit: str, new_maafw_tag: str | None = None):
    # 1. Update UPSTREAM_VERSIONS.json
    versions_data = json.loads(VERSIONS_FILE.read_text(encoding="utf-8"))
    tag_info = run_cmd(["git", "describe", "--tags", "--always"], cwd=MAAEND_ROOT)

    versions_data["components"]["maaend"]["pinned_commit"] = new_maaend_commit
    versions_data["components"]["maaend"]["pinned_tag"] = tag_info

    if new_maafw_tag:
        versions_data["components"]["maa_framework"]["pinned_release"] = new_maafw_tag

    VERSIONS_FILE.write_text(json.dumps(versions_data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    log("Updated UPSTREAM_VERSIONS.json")

    # 2. Update README.md pinned commit reference
    if README_FILE.is_file():
        content = README_FILE.read_text(encoding="utf-8")
        short_sha = new_maaend_commit[:7]
        content = re.sub(
            r"https://github\.com/MaaEnd/MaaEnd/commit/[0-9a-f]{40}",
            f"https://github.com/MaaEnd/MaaEnd/commit/{new_maaend_commit}",
            content,
        )
        content = re.sub(
            r"Commit \[`[0-9a-f]{7}`\]",
            f"Commit [`{short_sha}`]",
            content,
        )
        README_FILE.write_text(content, encoding="utf-8")
        log("Updated README.md commit links")


def set_github_output(key: str, value: str):
    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path and os.path.exists(output_path):
        with open(output_path, "a", encoding="utf-8") as f:
            f.write(f"{key}={value}\n")


def main():
    force_build = os.environ.get("FORCE_BUILD", "false").lower() == "true"
    versions_data = json.loads(VERSIONS_FILE.read_text(encoding="utf-8"))
    current_maafw = versions_data["components"]["maa_framework"]["pinned_release"]

    maaend_changed, old_c, new_c, subject = check_maaend_update()
    maafw_changed, new_maafw = check_maafw_update(current_maafw)

    has_changes = maaend_changed or maafw_changed or force_build

    if maaend_changed:
        update_maaend_submodule(new_c)
        update_records(new_c, new_maafw if maafw_changed else None)
    elif maafw_changed:
        update_records(old_c, new_maafw)

    summary = ""
    if maaend_changed and maafw_changed:
        summary = f"MaaEnd to {new_c[:7]} ({subject[:40]}) & MaaFW to {new_maafw}"
    elif maaend_changed:
        summary = f"MaaEnd to {new_c[:7]} ({subject[:40]})"
    elif maafw_changed:
        summary = f"MaaFramework to {new_maafw}"
    elif force_build:
        summary = "Forced rebuild triggered"
    else:
        summary = "No upstream changes"

    log(f"Result: has_changes={has_changes}, summary={summary}")
    set_github_output("has_changes", "true" if has_changes else "false")
    set_github_output("summary", summary)
    set_github_output("new_maaend_commit", new_c if maaend_changed else "")


if __name__ == "__main__":
    main()
