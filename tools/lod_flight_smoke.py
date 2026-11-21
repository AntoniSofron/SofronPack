"""Exercise the handoff in a disposable CI world and save screenshots for inspection."""
import gzip
import pathlib
import json
import shutil
import struct
import subprocess
import time

ROOT = pathlib.Path("run/saves/SofronLodE2E")
LOG = pathlib.Path("triple-clouds-128-fog.log")
READY = "Textured Extended World GPU draw issued"


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


def main():
    save = ROOT / "level.dat"
    raw = gzip.decompress(save.read_bytes())
    flag = b"\x01\x00\x0dallowCommands"
    assert raw.count(flag) == 1, "Expected commands flag in disposable save"
    index = raw.index(flag) + len(flag)
    save.write_bytes(gzip.compress(raw[:index] + b"\x01" + raw[index+1:]))
    fixture = pathlib.Path("src/main/java/pl/sofron/smoke/FlightProbe.java")
    fixture.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile("tools/lod-smoke/FlightProbe.java", fixture)
    manifest = pathlib.Path("src/main/resources/fabric.mod.json")
    metadata = json.loads(manifest.read_text())
    metadata["entrypoints"]["client"].append("pl.sofron.smoke.FlightProbe")
    manifest.write_text(json.dumps(metadata))
    deadline = time.monotonic() + 225
    with LOG.open("w") as out:
        process = subprocess.Popen(["gradle", "--no-daemon", "--console=plain", "runClient",
            "--args=--quickPlaySingleplayer SofronLodE2E"], stdout=out, stderr=subprocess.STDOUT)
        try:
            while "LOD_CAPTURE lod-return" not in LOG.read_text(errors="replace"):
                if process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("Flight probe failed, see client log")
                time.sleep(2)
            for label in ("origin", "night", "rain", "east", "return"):
                source = pathlib.Path(f"run/screenshots/lod-{label}.png")
                assert source.stat().st_size > 4096, f"Empty {label} capture"
                shutil.copyfile(source, source.name)
            for line in LOG.read_text(errors="replace").splitlines():
                if "LOD_FLIGHT" in line:
                    print(line)
            assert records(), "No complete snapshots persisted"
        finally:
            process.terminate()
            try:
                process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                process.kill()


if __name__ == "__main__":
    main()
