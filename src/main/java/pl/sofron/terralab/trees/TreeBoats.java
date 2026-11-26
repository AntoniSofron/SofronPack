package pl.sofron.terralab.trees;

import java.util.function.Supplier;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.entity.vehicle.boat.ChestBoat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.Item;

public final class TreeBoats {
    public record Pair(EntityType<Boat> boat, EntityType<ChestBoat> chestBoat, Item item, Item chestItem) {}
    public static final Pair PINE = create("pine");
    public static final Pair APPLE = create("apple");

    private static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath("sofronpack", name);
    }

    private static <T extends AbstractBoat> EntityType<T> entity(String name, EntityType.EntityFactory<T> factory) {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, id(name));
        EntityType<T> type = EntityType.Builder.of(factory, MobCategory.MISC)
                .noLootTable().sized(1.375F, 0.5625F).eyeHeight(0.5625F).clientTrackingRange(10).build(key);
        Registry.register(BuiltInRegistries.ENTITY_TYPE, key, type);
        return type;
    }

    private static Item item(String name, EntityType<? extends AbstractBoat> type) {
        return Registry.register(BuiltInRegistries.ITEM, id(name),
                new BoatItem(type, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id(name))).stacksTo(1)));
    }

    private static Pair create(String name) {
        Item[] drops = new Item[2];
        EntityType<Boat> boat = entity(name+"_boat", (type, level) -> new Boat(type, level, () -> drops[0]));
        EntityType<ChestBoat> chest = entity(name+"_chest_boat", (type, level) -> new ChestBoat(type, level, () -> drops[1]));
        drops[0] = item(name+"_boat", boat);
        drops[1] = item(name+"_chest_boat", chest);
        return new Pair(boat, chest, drops[0], drops[1]);
    }

    public static void register() {
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                Identifier.withDefaultNamespace("tools_and_utilities"))).register(output -> {
                    output.accept(PINE.item()); output.accept(PINE.chestItem());
                    output.accept(APPLE.item()); output.accept(APPLE.chestItem());
                });
    }
}
