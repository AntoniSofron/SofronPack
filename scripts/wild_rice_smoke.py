#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import subprocess
import threading
import time

from runtime_smoke import rcon, wait_ready

ROOT = pathlib.Path(__file__).resolve().parents[1]
RUN = ROOT / "run"
LOG = ROOT / "wild-rice-smoke.log"
PORT = 25575
PASSWORD = "terra-lab-26-3-test-only"

def expect(command: str, success: bool = True) -> str:
    response = rcon("execute if " + command)
    passed = "Test passed" in response or "count: 1" in response
    if passed != success:
        raise AssertionError(f"{command}: expected {success}, got {response!r}")
    return response

def main():
    RUN.mkdir(parents=True, exist_ok=True)
    (RUN / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (RUN / "server.properties").write_text(
        "level-name=sofronpack-wild-rice-smoke\n"
        "level-seed=3880636525852909587\n"
        "online-mode=false\n"
        "generate-structures=true\n"
        "difficulty=peaceful\n"
        "max-tick-time=-1\n"
        "view-distance=4\n"
        "simulation-distance=4\n"
        "enable-rcon=true\n"
        f"rcon.password={PASSWORD}\n"
        f"rcon.port={PORT}\n"
        "server-port=25566\n",
        encoding="utf-8",
    )
    lines = []
    print("Launching Fabric 26.3 for wild rice smoke...", flush=True)
    with LOG.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            ["gradle", "--no-daemon", "--console=plain", "runServer"],
            cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, bufsize=1)
        def reader():
            for line in process.stdout:
                log.write(line)
                log.flush()
                lines.append(line)
                if "ERROR" in line or "Done (" in line:
                    print(line.rstrip(), flush=True)
        thread = threading.Thread(target=reader, daemon=True)
        thread.start()
        try:
            wait_ready(process, lines, timeout=300)
            for biome in ("shrubland", "rainforest"):
                result = rcon(f"locate biome sofronpack:{biome}")
                if "blocks away" not in result:
                    raise AssertionError(f"Could not locate {biome}: {result}")
                print(result, flush=True)
            rcon("forceload add 32 32 47 47")
            rcon("fill 37 257 37 47 265 47 minecraft:air")
            rcon("fill 38 259 38 46 259 46 minecraft:dirt")
            rcon("fill 40 260 40 46 260 46 minecraft:water")
            rcon("fill 39 260 40 39 260 46 minecraft:dirt")
            result = rcon("place feature sofronpack:swamp_wild_rice 40 260 42")
            expect("block 40 260 42 sofronpack:wild_rice_crop[half=lower,age=7,waterlogged=true]")
            expect("block 40 261 42 sofronpack:wild_rice_crop[half=upper,age=7,waterlogged=false]")
            print("PASS: naturally generated wild rice has two mature halves", result, flush=True)

            rcon("setblock 44 261 42 minecraft:water")
            rcon("place feature sofronpack:swamp_wild_rice 44 260 42")
            expect("block 44 260 42 minecraft:water")
            expect("block 44 261 42 minecraft:water")
            rcon("setblock 45 259 42 minecraft:stone")
            rcon("place feature sofronpack:swamp_wild_rice 45 260 42")
            expect("block 45 260 42 minecraft:water")
            print("PASS: invalid depth and substrate rejected", flush=True)

            rcon("setblock 40 261 42 minecraft:air destroy")
            expect("block 40 260 42 minecraft:water")
            print("PASS: breaking top restores original water below", flush=True)

            rcon("place feature sofronpack:swamp_wild_rice 40 260 42")
            expect("block 40 260 42 sofronpack:wild_rice_crop[half=lower,age=7]")
            expect("block 40 261 42 sofronpack:wild_rice_crop[half=upper,age=7]")
            assert not list(RUN.rglob("sofronpack-extended-world")), "Dedicated server created a LOD archive"
            print("PASS: wild rice game runtime smoke", flush=True)
        finally:
            if process.poll() is None:
                try:
                    rcon("stop")
                except Exception:
                    pass
                try:
                    process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=15)

if __name__ == "__main__":
    main()
