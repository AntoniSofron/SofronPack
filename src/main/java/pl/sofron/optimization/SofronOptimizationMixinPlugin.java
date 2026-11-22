package pl.sofron.optimization;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Avoid duplicate, competing injections if the original standalone mod is
 * also present. SofronPack must not break a user's existing optimizations.
 */
public final class SofronOptimizationMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        FabricLoader loader = FabricLoader.getInstance();
        if (mixinClassName.contains("SofronFastGui")
                || mixinClassName.contains("SofronFastGlyph")
                || mixinClassName.contains("SofronGlPass")
                || mixinClassName.contains("SofronGlSurface")) {
            return !loader.isModLoaded("immediatelyfast");
        }
        if (mixinClassName.contains("SofronFastElytra")
                || mixinClassName.contains("SofronLithium"))
            return !loader.isModLoaded("lithium");
        if (mixinClassName.contains("SofronBackgroundFramerate"))
            return !loader.isModLoaded("dynamic_fps");
        if (mixinClassName.contains("SofronOreBitset"))
            return !loader.isModLoaded("c2me");
        return true;
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass,
                                   String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass,
                                    String mixinClassName, IMixinInfo mixinInfo) { }
}
