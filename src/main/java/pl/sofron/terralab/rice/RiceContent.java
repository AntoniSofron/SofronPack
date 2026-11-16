package pl.sofron.terralab.rice;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import pl.sofron.terralab.TerraLabMod;

/**
 * The very same rice item is a seed and the harvested crop. No food components
 * are assigned to it, so raw rice is not edible.
 */
public final class RiceContent {
    public static final Identifier RICE_ID =
            Identifier.fromNamespaceAndPath(TerraLabMod.MOD_ID, "rice");
    public static final Identifier CROP_ID =
            Identifier.fromNamespaceAndPath(TerraLabMod.MOD_ID, "rice_crop");

    public static final RiceCropBlock CROP = Registry.register(
            BuiltInRegistries.BLOCK, CROP_ID, new RiceCropBlock(
                    BlockBehaviour.Properties.ofFullCopy(net.minecraft.world.level.block.Blocks.WHEAT)
                            .setId(ResourceKey.create(Registries.BLOCK, CROP_ID))));

    public static final Item RICE = Registry.register(
            BuiltInRegistries.ITEM, RICE_ID, new BlockItem(CROP,
                    new Item.Properties().setId(ResourceKey.create(Registries.ITEM, RICE_ID))));

    public static final Identifier WILD_RICE_ID =
            Identifier.fromNamespaceAndPath(TerraLabMod.MOD_ID, "wild_rice");
    public static final Identifier WILD_RICE_CROP_ID =
            Identifier.fromNamespaceAndPath(TerraLabMod.MOD_ID, "wild_rice_crop");

    public static final WildRiceCropBlock WILD_RICE_CROP = Registry.register(
            BuiltInRegistries.BLOCK, WILD_RICE_CROP_ID, new WildRiceCropBlock(
                    BlockBehaviour.Properties.ofFullCopy(net.minecraft.world.level.block.Blocks.WHEAT)
                            .setId(ResourceKey.create(Registries.BLOCK, WILD_RICE_CROP_ID))));

    public static final Item WILD_RICE = Registry.register(
            BuiltInRegistries.ITEM, WILD_RICE_ID, new BlockItem(WILD_RICE_CROP,
                    new Item.Properties().setId(ResourceKey.create(Registries.ITEM, WILD_RICE_ID))));

    private RiceContent() {}

    public static void register() {
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                Identifier.withDefaultNamespace("natural_blocks")))
                .register(output -> { output.accept(RICE); output.accept(WILD_RICE); });
    }
}
