#!/usr/bin/env python3
"""Verify the current SofronPack copyright notice and metadata."""
from pathlib import Path
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
legal_names = re.compile(r"^(license|licence|notice|copying)(?:[._-].*)?$", re.I)
notices = [p for p in tracked if p and legal_names.fullmatch(Path(p).name)]
assert notices == ["LICENSE"], f"Unexpected legal files: {notices}"

central = (ROOT / "LICENSE").read_text(encoding="utf-8")
assert "Copyright (c) 2026 Antoni Sofron" in central
assert "All rights reserved." in central
mod = json.loads((ROOT / "src/main/resources/fabric.mod.json").read_text(encoding="utf-8"))
assert mod["license"] == "All Rights Reserved"
assert mod["authors"] == ["Antoni Sofron"]
print("PASS: centralized SofronPack copyright notice and matching metadata")
