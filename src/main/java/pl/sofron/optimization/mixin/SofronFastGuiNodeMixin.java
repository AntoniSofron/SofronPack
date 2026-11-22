/*
 * Adapted for SofronPack from ImmediatelyFast 26.3:
 * https://github.com/RaphiMC/ImmediatelyFast
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors.
 * Licensed under GNU LGPL v3 or later.
 * Source attribution and license details: THIRD_PARTY_OPTIMIZATIONS.md
 */
package pl.sofron.optimization.mixin;

import java.util.ArrayList;
import net.minecraft.client.renderer.state.gui.ScreenArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import pl.sofron.optimization.gui.SofronSpatialGuiArrayList;

/** Replace the GUI batch list with the indexed append-only equivalent. */
@Mixin(targets = "net.minecraft.client.renderer.state.gui.GuiRenderState$Node")
public abstract class SofronFastGuiNodeMixin {
    @Redirect(method = {"addItem", "addText", "addPicturesInPictureState", "addGuiElement"},
            at = @At(value = "NEW", target = "()Ljava/util/ArrayList;"))
    private ArrayList<? extends ScreenArea> sofron$indexedGuiList() {
        return new SofronSpatialGuiArrayList<>();
    }
}
