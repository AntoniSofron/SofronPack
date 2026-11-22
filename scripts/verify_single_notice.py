#!/usr/bin/env python3
"""Verify the repository keeps legal notices in one root document."""
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
tracked = [p for p in tracked if p]
legal_names = re.compile(r"^(license|licence|notice|copying)(?:[._-].*)?$", re.I)
notices = [p for p in tracked if legal_names.fullmatch(Path(p).name) or
           Path(p).name in ("THIRD_PARTY_OPTIMIZATIONS.md", "ATMOSPHERE-NOTICE.txt")]
assert notices == ["LICENSE"], f"Unexpected legal files: {notices}"

central = (ROOT / "LICENSE").read_text(encoding="utf-8")
assert "GNU LESSER GENERAL PUBLIC LICENSE" in central
assert "GNU GENERAL PUBLIC LICENSE" in central
assert "SofronPack" in central

for name in tracked:
    if not name.startswith(("src/main/java/", "src/test/java/")) or not name.endswith(".java"):
        continue
    source = (ROOT / name).read_text(encoding="utf-8")
    forbidden = ("Copyright (", "Copyright ©", "SPDX-License-Identifier:",
                 "LEGAl_NOTICE.md", "THIRD_PARTY_OPTIMIZATIONS.md",
                 "Licensed under", "GPL-3.0", "LGPL-3.0",
                 "me.cortex.voxy", "SofronLithium", "SofronFerrite")
    lower = source.lower()
    assert not any(term.lower() in lower for term in forbidden), name

mod = (ROOT / "src/main/resources/fabric.mod.json").read_text(encoding="utf-8")
import json
assert "license" not in json.loads(mod)
print("PASS: one central LICENSE and SofronPack-only Java namespaces")
