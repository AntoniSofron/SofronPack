#!/usr/bin/env python3
"""Opt-in Linux LOD128 benchmark in isolated git worktrees.

Requires Java 25, Gradle, Xvfb and Mesa drivers (EGL for OpenGL).
Does not install packages, modify the original checkout or run during Build.
"""
from __future__ import annotations

import argparse
import gzip
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PROBE = r'''package pl.sofron.smoke;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.util.*;
import java.lang.management.ManagementFactory;
public final class FrameProbe {
    static final com.sun.management.OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
    static final long[] times=new long[65536];
    static long init, first, last, cpu, read, written;
    static boolean seen; static long openingLast; static int opening50,opening100,opening250; static double worst;
    public static void draw(){if(init!=0 && !seen){seen=true;System.out.printf(Locale.ROOT,"BENCH_OVERVIEW first_visible_seconds=%.3f size_chunks=128x128%n",(System.nanoTime()-init)*1e-9);}}
    static int count; static boolean done;
    static long stat(String path,String name) {
        try {for(String s:Files.readAllLines(Path.of(path)))if(s.startsWith(name+":"))return Long.parseLong(s.split("\\s+")[1]);}catch(Exception e){throw new RuntimeException(e);}return -1;
    }
    public static void frame(Minecraft mc) {
        if(done||mc.getConnection()==null)return;
        long now=System.nanoTime();
        if(init==0){init=now;var c=mc.getConnection();c.sendCommand("gamemode spectator");c.sendCommand("tp @s 40 180 42 150 16");c.sendCommand("weather clear 600");c.sendCommand("time set noon");return;}
        if(now-init <= 10_000_000_000L) {
            if(openingLast!=0){double ms=(now-openingLast)*1e-6;worst=Math.max(worst,ms);if(ms>50)opening50++;if(ms>100)opening100++;if(ms>250)opening250++;}
            openingLast=now;
        }
        if(now-init<45_000_000_000L||Math.abs(mc.player.getY()-180)>1)return;
        if(last==0){first=last=now;cpu=os.getProcessCpuTime();read=stat("/proc/self/io","read_bytes");written=stat("/proc/self/io","write_bytes");return;}
        mc.player.setYRot(150f + 45f*(float)Math.sin((now-first)*1e-9*Math.PI/15));
        times[count++]=now-last;last=now;
        if(now-first<30_000_000_000L && count<times.length)return;
        done=true;
        double seconds=(now-first)*1e-9;
        Arrays.sort(times,0,count);
        System.out.printf(Locale.ROOT,"FRAME_RESULT n=%d seconds=%.3f fps=%.2f p99ms=%.2f over50=%d over100=%d over250=%d cpuCores=%.3f rssKiB=%d readBytes=%d writeBytes=%d rd=%d%n",count,seconds,count/seconds,times[(int)Math.ceil(count*.99)-1]*1e-6,over(50),over(100),over(250),(os.getProcessCpuTime()-cpu)*1e-9/seconds,stat("/proc/self/status","VmRSS"),stat("/proc/self/io","read_bytes")-read,stat("/proc/self/io","write_bytes")-written,mc.options.getEffectiveRenderDistance());
        System.out.printf(Locale.ROOT,"BENCH_OPENING over50=%d over100=%d over250=%d worst_ms=%.2f%n",opening50,opening100,opening250,worst);
        mc.execute(mc::stop);
    }
    static long over(int ms){return Arrays.stream(times,0,count).filter(t->t>ms*1000000L).count();}
}
'''


def run(args, cwd=ROOT, **kwargs):
    return subprocess.run(args, cwd=cwd, check=True, **kwargs)


def inject_once(path, old, new):
    source = path.read_text(encoding="utf-8")
    if source.count(old) != 1:
        raise RuntimeError(f"Benchmark hook missing or ambiguous: {path}")
    path.write_text(source.replace(old, new), encoding="utf-8")


def benchmark(ref, backend, logs, number, require_overview):
    sha = run(["git", "rev-parse", "--verify", ref + "^{commit}"],
              capture_output=True, text=True).stdout.strip()
    output = logs / f"{number:02d}-{sha[:12]}-{backend}.log"
    with tempfile.TemporaryDirectory(prefix="sofron-lod128-") as tmp:
        work = Path(tmp) / "work"
        run(["git", "worktree", "add", "--detach", str(work), sha])
        try:
            runtime = work / "run"
            for name in ("mods", "saves", "config"):
                (runtime / name).mkdir(parents=True, exist_ok=True)
            sodium = next(iter(sorted((work / "libs").glob("sodium*.jar"))), None)
            if sodium is None:
                raise RuntimeError("No bundled Sodium JAR")
            shutil.copy2(sodium, runtime / "mods" / sodium.name)
            with output.open("w", encoding="utf-8") as log:
                run(["python3", "scripts/wild_rice_smoke.py"], cwd=work,
                    stdout=log, stderr=subprocess.STDOUT, timeout=420)
                save = runtime / "saves" / "PerfWorld"
                shutil.copytree(runtime / "sofronpack-wild-rice-smoke", save)
                dat = save / "level.dat"
                raw = gzip.decompress(dat.read_bytes())
                key = b"\x01\x00\x0dallowCommands"
                offset = raw.index(key) + len(key)
                dat.write_bytes(gzip.compress(raw[:offset] + b"\x01" + raw[offset+1:]))
                (runtime / "config/sofronpack-extended-world.json").write_text(
                    '{"enabled":true}\n', encoding="utf-8")
                (runtime / "options.txt").write_text(
                    f'preferredGraphicsBackend:"{backend}"\nrenderDistance:32\n'
                    'simulationDistance:5\nenableVsync:false\nmaxFps:260\n'
                    'pauseOnLostFocus:false\n', encoding="utf-8")
                java = work / "src/main/java/pl/sofron"
                probe_path = java / "smoke/FrameProbe.java"
                if probe_path.exists():
                    raise RuntimeError("Ref already has a frame probe")
                probe_path.parent.mkdir(parents=True, exist_ok=True)
                probe_path.write_text(PROBE, encoding="utf-8")
                inject_once(java / "light/mixin/MinecraftFramePrepareMixin.java",
                            "var camera =",
                            "pl.sofron.smoke.FrameProbe.frame(mc);\n        var camera =")
                renderer = java / "extended/client/ExtendedWorldLodRenderer.java"
                has_overview = "overviewBuffer" in renderer.read_text(encoding="utf-8")
                if has_overview:
                    inject_once(renderer, "pass.drawIndexed(6, tile.quads, 0, 0, 0);",
                                "pass.drawIndexed(6, tile.quads, 0, 0, 0);"
                                " if (tile.region == null) pl.sofron.smoke.FrameProbe.draw();")
                elif require_overview:
                    raise RuntimeError("Ref has no opening overview renderer")
                env = dict(os.environ, LIBGL_ALWAYS_SOFTWARE="1",
                           MESA_GL_VERSION_OVERRIDE="4.6", MESA_GLSL_VERSION_OVERRIDE="460")
                if backend == "opengl":
                    env["SDL_VIDEO_FORCE_EGL"] = "1"
                else:
                    env.pop("SDL_VIDEO_FORCE_EGL", None)
                log.flush()
                run(["timeout", "300", "xvfb-run", "-a", "-s",
                     "-screen 0 1280x960x24 +extension GLX",
                     "gradle", "--no-daemon", "--console=plain", "runClient",
                     f"--args=--graphicsBackend {backend} --quickPlaySingleplayer PerfWorld --width 960 --height 540"],
                    cwd=work, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=330)
            text = output.read_text(encoding="utf-8", errors="replace")
            if not re.search(r"Using graphics backend\s+" + backend + r"\b", text, re.I):
                raise RuntimeError(f"Requested backend not verified: {output}")
            if "FRAME_RESULT " not in text or "Textured Extended World GPU draw issued" not in text:
                raise RuntimeError(f"Missing frame or GPU draw evidence: {output}")
            if (require_overview or has_overview) and "BENCH_OVERVIEW" not in text:
                raise RuntimeError(f"Opening overview draw not verified: {output}")
            for line in text.splitlines():
                if any(mark in line for mark in ("Using graphics backend", "BENCH_", "FRAME_RESULT ")):
                    print(line, flush=True)
            print("SAVE_BYTES", sum(p.stat().st_size for p in save.rglob("*") if p.is_file()))
        finally:
            run(["git", "worktree", "remove", "--force", str(work)])
    print(f"PASS: {sha[:12]} {backend}; log={output}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", action="append",
                        help="Clean commit or branch; repeat for A/B or B/A comparisons")
    parser.add_argument("--backend", choices=("vulkan", "opengl"), default="vulkan")
    parser.add_argument("--require-overview", action="store_true",
                        help="Fail if a selected ref has no 128x128 opening overview")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "bench-results")
    args = parser.parse_args()
    for command in ("git", "gradle", "xvfb-run", "timeout", "python3"):
        if shutil.which(command) is None:
            parser.error(f"Missing required command: {command}")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    logs = Path(tempfile.mkdtemp(prefix="lod128-", dir=args.output_dir.resolve()))
    for number, ref in enumerate(args.ref or ["HEAD"], 1):
        benchmark(ref, args.backend, logs, number, args.require_overview)


if __name__ == "__main__":
    main()
