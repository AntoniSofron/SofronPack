"""Exercise the handoff in a disposable CI world and save screenshots for inspection."""
import gzip
import pathlib
import re
import struct
import subprocess
import sys
import time

ROOT = pathlib.Path("run/saves/SofronLodE2E")
LOG = pathlib.Path("triple-clouds-128-fog.log")
READY = "Textured Extended World GPU draw issued"


def command(text):
    subprocess.run(["xdotool", "key", "t"], check=True)
    time.sleep(0.8)
    subprocess.run(["xdotool", "type", "--delay", "40", "/" + text], check=True)
    subprocess.run(["xdotool", "key", "Return"], check=True)
    time.sleep(1)


def records():
    unique = set()
    files = list(ROOT.glob("sofronpack-extended-world/*overworld*.bin"))
    if not files:
        return unique
    with files[0].open("rb") as stream:
        stream.seek(8)
        while len(header := stream.read(8)) == 8:
            size = struct.unpack(">I", header[:4])[0]
            payload = stream.read(size)
            if len(payload) != size:
                break
            unique.add(struct.unpack(">ii", payload[:8]))
    return unique


def capture(label):
    subprocess.run(["import", "-window", "root", "lod-" + label + ".png"], check=True)
    print(label, "unique persisted chunks", len(records()), flush=True)


def main():
    save = ROOT / "level.dat"
    raw = gzip.decompress(save.read_bytes())
    flag = b"\x01\x00\x0dallowCommands"
    assert raw.count(flag) == 1, "Expected commands flag in disposable save"
    index = raw.index(flag) + len(flag)
    save.write_bytes(gzip.compress(raw[:index] + b"\x01" + raw[index+1:]))
    deadline = time.monotonic() + 150
    with LOG.open("w") as out:
        process = subprocess.Popen(["gradle", "--no-daemon", "--console=plain", "runClient",
            "--args=--quickPlaySingleplayer SofronLodE2E"], stdout=out, stderr=subprocess.STDOUT)
        try:
            while READY not in LOG.read_text(errors="replace"):
                if process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("World did not draw terrain")
                time.sleep(2)
            time.sleep(3)
            command("gamemode spectator")
            command("tp @s 40 180 42 150 16")
            time.sleep(15)
            capture("origin")
            command("tp @s 360 180 42 -90 16")
            time.sleep(15)
            capture("east")
            command("tp @s 40 180 42 150 16")
            time.sleep(15)
            capture("return")
            log = LOG.read_text(errors="replace")
            if not re.search(r"Teleported.*360", log, re.I):
                raise AssertionError("Could not verify the 20-chunk camera move")
            if not records():
                raise AssertionError("World cache contained no complete snapshots")
        finally:
            process.terminate()
            try:
                process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                process.kill()


if __name__ == "__main__":
    main()
