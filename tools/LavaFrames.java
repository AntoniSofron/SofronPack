package pl.sofron.smoke;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.util.*;
public class LavaFrames {
 static long ready,last,start,lastMove; static boolean setup,done;
 static int n; static long[] times=new long[65536];
 public static void frame(Minecraft mc) {
  if(done || mc.getSingleplayerServer()==null || mc.player==null || mc.level==null)return;
  long now=System.nanoTime();
  if(!setup){setup=true;var s=mc.getSingleplayerServer();s.execute(()->{var source=s.createCommandSourceStack();s.getCommands().performPrefixedCommand(source,"gamemode spectator @a");s.getCommands().performPrefixedCommand(source,"tp @a 48 263 47 180 28");});return;}
  if(Math.abs(mc.player.getY()-263)>1 || !mc.level.getBlockState(new BlockPos(48,260,47)).is(Blocks.LAVA))return;
  if(pl.sofron.light.SofronPackLightClient.LIGHTS.chunkIndex().indexedLights()<4096)return;
  if(ready==0)ready=now;
  if(now-ready<12_000_000_000L)return;
  double phase=(now-ready)*1e-9;
  mc.player.setYRot((float)(180+55*Math.sin(phase)));
  if(now-lastMove>500_000_000L){
    lastMove=now;var s=mc.getSingleplayerServer(); var cmd="tp @a "+(48+2*Math.sin(phase))+" 263 47";
    s.execute(()->s.getCommands().performPrefixedCommand(s.createCommandSourceStack(),cmd));
  }
  if(last==0){start=last=now;return;}
  times[n++]=now-last;last=now;
  if(now-start<24_000_000_000L && n<times.length)return;
  Arrays.sort(times,0,n);done=true;
  System.out.printf(Locale.ROOT,"LAVA_RESULT frames=%d fps=%.2f p99ms=%.2f emitters=%d%n",n,n/((now-start)*1e-9),times[(int)Math.ceil(n*.99)-1]*1e-6,pl.sofron.light.SofronPackLightClient.LIGHTS.chunkIndex().indexedLights());
  mc.execute(mc::stop);
 }
}
