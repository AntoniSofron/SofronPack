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


# Disposable A/B instrumentation; only installed in the CI measurement branch.
def benchmark():
    import shutil, os, json, gzip
    subprocess.run(["git","fetch","--depth=1","origin","f07e57be7c266b256e5502d3d748aef8dbb9bcd8"],check=True)
    subprocess.run(["git","fetch","--depth=1","origin","9bfc0a6e65e9c8bb93d5473e38ee443b765cb656"],check=True)
    (RUN/"config").mkdir(exist_ok=True)
    (RUN/"mods").mkdir(exist_ok=True)
    shutil.copy(next((ROOT/"libs").glob("sodium*.jar")),RUN/"mods")
    (RUN/"config/sofronpack-extended-world.json").write_text('{"enabled":true}')
    orig = (RUN/"sofronpack-wild-rice-smoke")
    probe=ROOT/"src/main/java/pl/sofron/smoke/FrameProbe.java"
    probe.parent.mkdir(parents=True,exist_ok=True)
    probe.write_text("package pl.sofron.smoke;\nimport net.minecraft.client.Minecraft;\nimport java.nio.file.*;\nimport java.util.*;\nimport java.lang.management.ManagementFactory;\npublic final class FrameProbe {\n    static final com.sun.management.OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);\n    static final long[] times=new long[65536];\n    static long init, first, last, cpu, read, written;\n    static boolean seen; static long openingLast; static int opening50,opening100,opening250; static double worst;\n    public static void draw(){if(init!=0 && !seen){seen=true;System.out.printf(Locale.ROOT,\"BENCH_OVERVIEW first_visible_seconds=%.3f size_chunks=128x128%n\",(System.nanoTime()-init)*1e-9);}}\n    static int count; static boolean done;\n    static long stat(String path,String name) {\n        try {for(String s:Files.readAllLines(Path.of(path)))if(s.startsWith(name+\":\"))return Long.parseLong(s.split(\"\\\\s+\")[1]);}catch(Exception e){throw new RuntimeException(e);}return -1;\n    }\n    public static void frame(Minecraft mc) {\n        if(done||mc.getConnection()==null)return;\n        long now=System.nanoTime();\n        if(init==0){init=now;var c=mc.getConnection();c.sendCommand(\"gamemode spectator\");c.sendCommand(\"tp @s 40 180 42 150 16\");c.sendCommand(\"weather clear 600\");c.sendCommand(\"time set noon\");return;}\n        if(now-init <= 10_000_000_000L) {\n            if(openingLast!=0){double ms=(now-openingLast)*1e-6;worst=Math.max(worst,ms);if(ms>50)opening50++;if(ms>100)opening100++;if(ms>250)opening250++;}\n            openingLast=now;\n        }\n        if(now-init<45_000_000_000L||Math.abs(mc.player.getY()-180)>1)return;\n        if(last==0){first=last=now;cpu=os.getProcessCpuTime();read=stat(\"/proc/self/io\",\"read_bytes\");written=stat(\"/proc/self/io\",\"write_bytes\");return;}\n        mc.player.setYRot(150f + 45f*(float)Math.sin((now-first)*1e-9*Math.PI/15));\n        times[count++]=now-last;last=now;\n        if(now-first<30_000_000_000L && count<times.length)return;\n        done=true;\n        double seconds=(now-first)*1e-9;\n        Arrays.sort(times,0,count);\n        System.out.printf(Locale.ROOT,\"FRAME_RESULT n=%d seconds=%.3f fps=%.2f p99ms=%.2f over50=%d over100=%d over250=%d cpuCores=%.3f rssKiB=%d readBytes=%d writeBytes=%d rd=%d%n\",count,seconds,count/seconds,times[(int)Math.ceil(count*.99)-1]*1e-6,over(50),over(100),over(250),(os.getProcessCpuTime()-cpu)*1e-9/seconds,stat(\"/proc/self/status\",\"VmRSS\"),stat(\"/proc/self/io\",\"read_bytes\")-read,stat(\"/proc/self/io\",\"write_bytes\")-written,mc.options.getEffectiveRenderDistance());\n        System.out.printf(Locale.ROOT,\"BENCH_OPENING over50=%d over100=%d over250=%d worst_ms=%.2f%n\",opening50,opening100,opening250,worst);\n        mc.execute(mc::stop);\n    }\n    static long over(int ms){return Arrays.stream(times,0,count).filter(t->t>ms*1000000L).count();}\n}\n")
    hook=ROOT/"src/main/java/pl/sofron/light/mixin/MinecraftFramePrepareMixin.java"
    hook.write_text(hook.read_text().replace("var camera =","pl.sofron.smoke.FrameProbe.frame(mc);\n        var camera ="))
    cfg=RUN/"options.txt"
    for label,sha in [('A1','f07e57be7c266b256e5502d3d748aef8dbb9bcd8'),('B1','9bfc0a6e65e9c8bb93d5473e38ee443b765cb656')]:
        for rel in ('src/main/java/pl/sofron/extended/client/ExtendedWorldLodRenderer.java','src/main/java/pl/sofron/extended/ExtendedVoxelStore.java','src/main/java/pl/sofron/extended/ExtendedTerrainPreview.java','src/main/java/pl/sofron/extended/ExtendedSurfaceMesh.java'):
            (ROOT/rel).write_bytes(subprocess.check_output(['git','show',sha+':'+rel]))
        renderer=ROOT/'src/main/java/pl/sofron/extended/client/ExtendedWorldLodRenderer.java'
        if label.startswith('B'):
            renderer.write_text(renderer.read_text().replace('pass.drawIndexed(6, tile.quads, 0, 0, 0);','pass.drawIndexed(6, tile.quads, 0, 0, 0); if(tile.region==null) pl.sofron.smoke.FrameProbe.draw();'))
        save=RUN/"saves/PerfWorld"
        if save.exists(): shutil.rmtree(save)
        shutil.copytree(orig,save)
        dat=save/'level.dat'
        raw=gzip.decompress(dat.read_bytes())
        key=b'\x01\x00\x0dallowCommands'
        i=raw.index(key)+len(key)
        dat.write_bytes(gzip.compress(raw[:i]+b'\x01'+raw[i+1:]))
        cfg.write_text('renderDistance:32\nsimulationDistance:5\nenableVsync:false\nmaxFps:260\npauseOnLostFocus:false\n')
        result=subprocess.run(['timeout','300','xvfb-run','-a','-s','-screen 0 1280x960x24 +extension GLX','gradle','--console=plain','runClient','--args=--graphicsBackend vulkan --quickPlaySingleplayer PerfWorld --width 960 --height 540'],env=dict(os.environ,LIBGL_ALWAYS_SOFTWARE='1',MESA_GL_VERSION_OVERRIDE='4.6'),stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
        for line in result.stdout.splitlines():
            if 'BENCH_' in line or 'FRAME_RESULT ' in line or 'GPU draw issued' in line: print(label,line,flush=True)
        if label.startswith('B') and 'BENCH_OVERVIEW' not in result.stdout: print(result.stdout[-4000:],flush=True); raise RuntimeError('overview draw missing')
        if 'FRAME_RESULT ' not in result.stdout or 'GPU draw issued' not in result.stdout or result.returncode:
            raise RuntimeError(result.stdout[-7000:])
        print(label,'SAVE_BYTES',sum(p.stat().st_size for p in save.rglob('*') if p.is_file()),flush=True)

if __name__ == "__main__":
    main()

    benchmark()
