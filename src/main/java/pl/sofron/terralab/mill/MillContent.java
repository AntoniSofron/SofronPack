package pl.sofron.terralab.mill;

import java.util.function.Function;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import pl.sofron.terralab.TerraLabMod;

public final class MillContent {
    private MillContent() {}

    public static final Item FLOUR = item("flour");
    public static final Block MILL = block("mill", Blocks.OAK_PLANKS, MillBlock::new);
    public static final Block SHAFT = block("mill_shaft", Blocks.OAK_FENCE, MillShaftBlock::new);
    public static final Block SAILS = block("mill_sails", Blocks.OAK_PLANKS, MillSailsBlock::new);

    private static Item item(String name) {
        Identifier id = Identifier.fromNamespaceAndPath(TerraLabMod.MOD_ID, name);
        return Registry.register(BuiltInRegistries.ITEM, id,
                new Item(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))));
    }

    private static Block block(String name, Block base,
                               Function<BlockBehaviour.Properties, Block> factory) {
        Identifier id = Identifier.fromNamespaceAndPath(TerraLabMod.MOD_ID, name);
        var key = ResourceKey.create(Registries.BLOCK, id);
        Block block = factory.apply(BlockBehaviour.Properties.ofFullCopy(base).noOcclusion().setId(key));
        Registry.register(BuiltInRegistries.BLOCK, key, block);
        Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block,
                new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)).useBlockDescriptionPrefix()));
        return block;
    }

    public static void register() {
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                Identifier.withDefaultNamespace("ingredients")))
                .register(output -> output.accept(FLOUR));
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                Identifier.withDefaultNamespace("functional_blocks")))
                .register(output -> { output.accept(MILL); output.accept(SHAFT); output.accept(SAILS); });
    }
}
