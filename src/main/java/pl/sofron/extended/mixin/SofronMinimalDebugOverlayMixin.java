package pl.sofron.extended.mixin;

import com.mojang.blaze3d.platform.Window;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.sofron.extended.SofronDebugHudSelection;

/**
 * Replace the entire F3 drawing operation, not the player's saved debug profile:
 * everyone gets precisely the vanilla FPS line, spectators additionally get the
 * selected vanilla version/position/hardware information on the right.
 *
 * No TPS, debug help, charts, RAM, renderer diagnostics or additional entries.
 * Vanilla DebugScreenEntries still supply live values and the original
 * extractLines method preserves their text/background style.
 */
@Mixin(DebugScreenOverlay.class)
public abstract class SofronMinimalDebugOverlayMixin {
    @Shadow
    private void extractLines(GuiGraphicsExtractor graphics, List<String> lines,
                              boolean alignLeft, int scaledScreenWidth) {
        throw new AssertionError("Mixin shadow was not applied");
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true, require = 1)
    private void sofron$minimalDebugOverlay(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        ci.cancel(); // Never allow the full vanilla F3 list or debug graphs to draw.

        Minecraft mc = Minecraft.getInstance();
        if (!mc.isGameLoadFinished() || !mc.debugEntries.isOverlayVisible()
                || (mc.gui.hud.isHidden() && mc.gui.screen() == null)) {
            return;
        }

        List<String> left = sofron$readEntry(mc, DebugScreenEntries.FPS);
        List<String> right = new ArrayList<>();
        if (mc.player != null && mc.player.isSpectator()) {
            right.addAll(sofron$readEntry(mc, DebugScreenEntries.GAME_VERSION));
            right.add("");
            right.addAll(SofronDebugHudSelection.positionOnly(
                    sofron$readEntry(mc, DebugScreenEntries.PLAYER_POSITION)));
            right.add("");
            right.addAll(sofron$readEntry(mc, DebugScreenEntries.SYSTEM_SPECS));
        }

        graphics.nextStratum();
        Window window = mc.getWindow();
        int ordinaryScale = window.getGuiScale();
        int debugScale = mc.options.debugGuiScale().get();
        if (debugScale == -1) {
            debugScale = ordinaryScale;
        } else if (debugScale == 0) {
            debugScale = window.calculateScale(0, mc.isEnforceUnicode()) / 2;
        } else {
            debugScale = window.calculateScale(debugScale, mc.isEnforceUnicode());
        }

        graphics.pose().pushMatrix();
        try {
            int width = graphics.guiWidth();
            if (debugScale > 0 && debugScale < ordinaryScale) {
                graphics.pose().scale((float) debugScale / ordinaryScale,
                        (float) debugScale / ordinaryScale);
                width = window.getWidth() / debugScale;
            }
            extractLines(graphics, left, true, width);
            if (!right.isEmpty()) extractLines(graphics, right, false, width);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private static List<String> sofron$readEntry(Minecraft mc, Identifier id) {
        List<String> lines = new ArrayList<>();
        DebugScreenEntry entry = DebugScreenEntries.getEntry(id);
        if (entry == null) return lines;

        DebugScreenDisplayer output = new DebugScreenDisplayer() {
            @Override public void addPriorityLine(String line) {
                lines.add(line);
            }

            @Override public void addLine(String line) {
                lines.add(line);
            }

            @Override public void addToGroup(Identifier group, Collection<String> values) {
                lines.addAll(values);
            }

            @Override public void addToGroup(Identifier group, String line) {
                lines.add(line);
            }
        };
        // These four entries do not use chunk data, so no chunk loads are needed.
        entry.display(output, mc.level, null, null);
        return lines;
    }
}
