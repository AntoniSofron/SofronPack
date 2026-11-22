/*
 * Based on the 26.3 framerate-limiter strategy in Dynamic FPS:
 * https://github.com/juliand665/Dynamic-FPS
 * Reimplemented as an isolated no-dependency, unfocused-window limiter.
 * For source attribution see THIRD_PARTY_OPTIMIZATIONS.md.
 */
package pl.sofron.optimization.mixin;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Does not change gameplay or FPS when Minecraft is focused. Saves GPU time
 * when tabbed out, without interrupting vanilla world simulation or audio.
 */
@Mixin(FramerateLimitTracker.class)
public abstract class SofronBackgroundFramerateMixin {
    private static final int INACTIVE_FPS = 15;

    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void sofron$limitUnfocusedWindow(CallbackInfoReturnable<Integer> cir) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return;
        Window window = minecraft.getWindow();
        if (window == null) return;
        long flags = SDLVideo.SDL_GetWindowFlags(window.handle());
        if ((flags & SDLVideo.SDL_WINDOW_INPUT_FOCUS) == 0) {
            cir.setReturnValue(INACTIVE_FPS);
        }
    }
}
