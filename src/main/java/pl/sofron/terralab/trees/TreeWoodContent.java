package pl.sofron.terralab.trees;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityType;
import net.fabricmc.fabric.api.item.v1.BlockTransformerHelper;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.item.*;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.sounds.AmbientLeavesBlockSoundPlayer;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.minecraft.world.level.levelgen.feature.Feature;

public final class TreeWoodContent {
    public record Family(Block log, Block wood, Block strippedLog, Block strippedWood,
                         Block planks, Block leaves, Block sapling) {}
    private static final List<Item> BUILDING = new ArrayList<>();
    private static final List<Item> NATURAL = new ArrayList<>();
    private static final List<Item> FUNCTIONAL = new ArrayList<>();
    public static final Family PINE = create("pine", true);
    public static final Family APPLE = create("apple", false);
    public static final Family FIR = create("fir", true);
    public static final Family CHESTNUT = create("chestnut", false);
    public static final Item CHESTNUT_ITEM = Registry.register(BuiltInRegistries.ITEM, id("chestnut"),
            new Item(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id("chestnut")))
                    .food(new FoodProperties.Builder().nutrition(1).saturationModifier(0.5F).build())));
    public static final Block WHITE_PETALS = part("white_petals", Blocks.PINK_PETALS,
            p -> new FlowerBedBlock(p, 3), NATURAL, 0, 0);
    public static final Block FALLEN_CHESTNUTS = part("fallen_chestnuts", Blocks.LEAF_LITTER,
            LeafLitterBlock::new, NATURAL, 0, 0);

    private static Identifier id(String name) { return Identifier.fromNamespaceAndPath("sofronpack", name); }
    private static ResourceKey<Feature> feature(String name) { return ResourceKey.create(Registries.FEATURE, id(name)); }
    private static Block block(String name, Block base, Function<BlockBehaviour.Properties, Block> make) {
        var key = ResourceKey.create(Registries.BLOCK, id(name));
        return Registry.register(BuiltInRegistries.BLOCK, key, make.apply(BlockBehaviour.Properties.ofFullCopy(base).setId(key)));
    }
    private static Item.Properties itemProps(String name) {
        return new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id(name))).useBlockDescriptionPrefix();
    }
    private static Block part(String name, Block base, Function<BlockBehaviour.Properties, Block> make,
                              List<Item> tab, int flame, int spread) {
        Block b = block(name, base, make);
        tab.add(Registry.register(BuiltInRegistries.ITEM, id(name), new BlockItem(b, itemProps(name))));
        FlammableBlockRegistry.getDefaultInstance().add(b, flame, spread);
        return b;
    }
    private static Family create(String n, boolean pine) {
        BlockSetType set = pine ? BlockSetType.SPRUCE : BlockSetType.OAK;
        WoodType type = pine ? WoodType.SPRUCE : WoodType.OAK;
        Block log = part(n+"_log", pine?Blocks.SPRUCE_LOG:Blocks.OAK_LOG, RotatedPillarBlock::new, BUILDING, 5, 5);
        Block wood = part(n+"_wood", pine?Blocks.SPRUCE_WOOD:Blocks.OAK_WOOD, RotatedPillarBlock::new, BUILDING, 5, 5);
        Block strippedLog = part("stripped_"+n+"_log", pine?Blocks.STRIPPED_SPRUCE_LOG:Blocks.STRIPPED_OAK_LOG, RotatedPillarBlock::new, BUILDING, 5, 5);
        Block strippedWood = part("stripped_"+n+"_wood", pine?Blocks.STRIPPED_SPRUCE_WOOD:Blocks.STRIPPED_OAK_WOOD, RotatedPillarBlock::new, BUILDING, 5, 5);
        BlockTransformerHelper.registerStripping(log, strippedLog.defaultBlockState());
        BlockTransformerHelper.registerStripping(wood, strippedWood.defaultBlockState());
        Block planks = part(n+"_planks", Blocks.OAK_PLANKS, Block::new, BUILDING, 5, 20);
        Block leaves = part(n+"_leaves", pine?Blocks.SPRUCE_LEAVES:Blocks.OAK_LEAVES,
                p -> pine ? new LeavesBlock(AmbientLeavesBlockSoundPlayer.noAmbientSound(), p)
                          : new TintedParticleLeavesBlock(0.01F, p), NATURAL, 30, 60);
        WeightedList<ResourceKey<Feature>> normal = pine ? WeightedList.of(feature(n+"_tree"))
                : WeightedList.<ResourceKey<Feature>>builder().add(feature(n+"_tree"), 4).add(feature("fancy_"+n+"_tree"), 1).build();
        WeightedList<ResourceKey<Feature>> mega = pine ? WeightedList.of(feature("mega_"+n+"_tree"))
                : WeightedList.<ResourceKey<Feature>>builder().build();
        TreeGrower grower = new TreeGrower("sofronpack:"+n, normal, mega,
                WeightedList.<ResourceKey<Feature>>builder().build(), feature(n+"_tree"));
        Block sapling = part(n+"_sapling", pine?Blocks.SPRUCE_SAPLING:Blocks.OAK_SAPLING,
                p -> new SaplingBlock(grower,p), NATURAL, 60, 100);
        block("potted_"+n+"_sapling", Blocks.POTTED_OAK_SAPLING, p -> new FlowerPotBlock(sapling,p));
        part(n+"_stairs", Blocks.OAK_STAIRS, p -> new StairBlock(planks.defaultBlockState(),p), BUILDING, 5,20);
        part(n+"_slab", Blocks.OAK_SLAB, SlabBlock::new, BUILDING,5,20);
        part(n+"_fence", Blocks.OAK_FENCE, FenceBlock::new, BUILDING,5,20);
        part(n+"_fence_gate", Blocks.OAK_FENCE_GATE, p -> new FenceGateBlock(type,p), BUILDING,5,20);
        part(n+"_door", Blocks.OAK_DOOR, p -> new DoorBlock(set,p), BUILDING,5,20);
        part(n+"_trapdoor", Blocks.OAK_TRAPDOOR, p -> new TrapDoorBlock(set,p), BUILDING,5,20);
        part(n+"_button", Blocks.OAK_BUTTON, p -> new ButtonBlock(set,30,p), FUNCTIONAL,5,20);
        part(n+"_pressure_plate", Blocks.OAK_PRESSURE_PLATE, p -> new PressurePlateBlock(set,p), FUNCTIONAL,5,20);
        Block shelf = part(n+"_shelf", Blocks.OAK_SHELF, ShelfBlock::new, FUNCTIONAL,5,20);
        ((FabricBlockEntityType)(Object)BlockEntityTypes.SHELF).addValidBlock(shelf);
        Block sign = block(n+"_sign", Blocks.OAK_SIGN, p -> new StandingSignBlock(type,p));
        Block wall = block(n+"_wall_sign", Blocks.OAK_WALL_SIGN, p -> new WallSignBlock(type,p));
        Block hanging = block(n+"_hanging_sign", Blocks.OAK_HANGING_SIGN, p -> new CeilingHangingSignBlock(type,p));
        Block wallHanging = block(n+"_wall_hanging_sign", Blocks.OAK_WALL_HANGING_SIGN, p -> new WallHangingSignBlock(type,p));
        FUNCTIONAL.add(Registry.register(BuiltInRegistries.ITEM,id(n+"_sign"),
                new StandingAndWallBlockItem(sign,wall,Direction.DOWN,itemProps(n+"_sign").stacksTo(16))));
        FUNCTIONAL.add(Registry.register(BuiltInRegistries.ITEM,id(n+"_hanging_sign"),
                new HangingSignItem(hanging,wallHanging,itemProps(n+"_hanging_sign").stacksTo(16))));
        for (Block b : new Block[]{sign,wall}) ((FabricBlockEntityType)(Object)BlockEntityTypes.SIGN).addValidBlock(b);
        for (Block b : new Block[]{hanging,wallHanging}) ((FabricBlockEntityType)(Object)BlockEntityTypes.HANGING_SIGN).addValidBlock(b);
        return new Family(log,wood,strippedLog,strippedWood,planks,leaves,sapling);
    }
    public static void register() {
        TreeBoats.register();
        tab("food_and_drinks", List.of(CHESTNUT_ITEM));
        tab("building_blocks", BUILDING);
        tab("natural_blocks", NATURAL);
        tab("functional_blocks", FUNCTIONAL);
    }
    private static void tab(String name, List<Item> items) {
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                Identifier.withDefaultNamespace(name))).register(output -> items.forEach(output::accept));
    }
}
