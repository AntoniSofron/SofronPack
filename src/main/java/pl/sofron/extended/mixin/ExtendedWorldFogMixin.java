package pl.sofron.extended.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.sofron.extended.ExtendedWorldConfig;
import pl.sofron.atmosphere.ClassicFogProfile;
import pl.sofron.extended.ExtendedLodFog;
import pl.sofron.atmosphere.AtmospherePresets;

/**
 * The modern 128-chunk fog remains the authority whenever Extended World
 * renders Overworld terrain in integrated singleplayer. Otherwise, replace
 * vanilla's fog start with the classic-fog source pack's start/3 behaviour.
 * Both paths share one mixin at RETURN, before Sodium snapshots FogData.
 */
@Mixin(FogRenderer.class)
public abstract class ExtendedWorldFogMixin {
    @Unique private static boolean sofron$logged;

    // Sodium snapshots these fields at RETURN with the default injection order (1000).
    @Inject(method = "setupFog", at = @At("RETURN"), order = 900)
    private void sofron$extendTerrainFog(Camera camera, int renderDistanceInChunks,
            DeltaTracker deltaTracker, float darkenWorldAmount, ClientLevel level,
            CallbackInfoReturnable<FogData> cir) {
        FogData fog = cir.getReturnValue();
        if (fog == null) return;

        // The uploaded classic-fog pack reduces BOTH starts to one third,
        // leaving the fog end and color unchanged. Apply this after the
        // atmosphere's biome/weather/cave rules, but never to the extended
        // 128-chunk fog where these fields encode our volumetric parameters.
        boolean extendedWorld = ExtendedWorldConfig.get().enabled && level != null
                && pl.sofron.extended.client.RemoteLodCache.canRender(net.minecraft.client.Minecraft.getInstance(), level)
                && level.dimension().equals(Level.OVERWORLD)
                && camera.getFluidInCamera() == FogType.NONE;
        if (!extendedWorld) {
            fog.environmentalStart = ClassicFogProfile.start(fog.environmentalStart);
            fog.renderDistanceStart = ClassicFogProfile.start(fog.renderDistanceStart);
            return;
        }

        // Minecraft's normal terrain disappears completely at the selected
        // render distance, leaving an unnatural fog wall in front of LODs.
        // Keep normal chunk loading/simulation untouched while shifting only
        // this artificial distance fade to the *same* distant-world range.
        // Real environmental fog end/start still wins in severe conditions.
        float start = pl.sofron.extended.ExtendedLodHandoff.nearStartChunks(renderDistanceInChunks) * 16f;
        float end = ExtendedWorldConfig.DISTANT_RADIUS_CHUNKS * 16f;
        fog.renderDistanceStart = start;
        fog.renderDistanceEnd = end;

        // Environmental end encodes weather/cave influence (0..1). Start
        // encodes the 8%-per-band altitude bonus, for both terrain pipelines.
        // The original environmental fog remains active outside this opt-in
        // singleplayer Overworld path, including underwater and lava.
        float influence = Math.clamp(AtmospherePresets.distantFogBlend(), 0f, 1f);
        fog.environmentalStart = ExtendedLodFog.environmentalStartForAltitude(camera.position().y);
        fog.environmentalEnd = end - 1024f * influence;
        if (!sofron$logged) {
            sofron$logged = true;
            System.out.println("[SofronPack] Unified Extended World fog active: normal + LOD terrain, 128 chunks");
        }
    }
}
