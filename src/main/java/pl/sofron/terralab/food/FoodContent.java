package pl.sofron.terralab.food;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import pl.sofron.terralab.TerraLabMod;

public final class FoodContent {
    public static final Item RAW_MEAT_PIECES = food("raw_meat_pieces", 1, 1, false);
    public static final Item COOKED_MEAT_PIECES = food("cooked_meat_pieces", 4, 4, false);
    public static final Item RAW_FISH_PIECES = food("raw_fish_pieces", 1, 1, false);
    public static final Item COOKED_FISH_PIECES = food("cooked_fish_pieces", 3, 4, false);
    public static final Item RAW_POULTRY_PIECES = food("raw_poultry_pieces", 1, 1, false);
    public static final Item COOKED_POULTRY_PIECES = food("cooked_poultry_pieces", 3, 4, false);
    public static final Item MIXED_RICE_BOWL = food("mixed_rice_bowl", 8, 10, true);
    public static final Item RICE_MEAT_BOWL = food("rice_meat_bowl", 12, 14, true);
    public static final Item RICE_FISH_BOWL = food("rice_fish_bowl", 10, 14, true);
    public static final Item RICE_POULTRY_BOWL = food("rice_poultry_bowl", 12, 12, true);
    public static final Item MASHED_POTATOES_BOWL = food("mashed_potatoes_bowl", 4, 4, true);
    public static final Item MASHED_POTATOES_MEAT_BOWL = food("mashed_potatoes_meat_bowl", 12, 14, true);
    public static final Item MASHED_POTATOES_FISH_BOWL = food("mashed_potatoes_fish_bowl", 10, 14, true);
    public static final Item MASHED_POTATOES_POULTRY_BOWL = food("mashed_potatoes_poultry_bowl", 12, 12, true);

    private FoodContent() {}

    private static Item food(String name, int hunger, float saturation, boolean bowl) {
        Identifier id = Identifier.fromNamespaceAndPath(TerraLabMod.MOD_ID, name);
        Item.Properties properties = new Item.Properties()
                .setId(ResourceKey.create(Registries.ITEM, id))
                .food(new FoodProperties.Builder()
                        .nutrition(hunger)
                        .saturationModifier(saturation / (2.0F * hunger))
                        .build());
        if (bowl) properties.stacksTo(1).usingConvertsTo(Items.BOWL);
        return Registry.register(BuiltInRegistries.ITEM, id, new Item(properties));
    }

    public static void register() {
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                Identifier.withDefaultNamespace("food_and_drinks")))
                .register(output -> {
                    output.accept(RAW_MEAT_PIECES);
                    output.accept(COOKED_MEAT_PIECES);
                    output.accept(RAW_FISH_PIECES);
                    output.accept(COOKED_FISH_PIECES);
                    output.accept(RAW_POULTRY_PIECES);
                    output.accept(COOKED_POULTRY_PIECES);
                    output.accept(MIXED_RICE_BOWL);
                    output.accept(RICE_MEAT_BOWL);
                    output.accept(RICE_FISH_BOWL);
                    output.accept(RICE_POULTRY_BOWL);
                    output.accept(MASHED_POTATOES_BOWL);
                    output.accept(MASHED_POTATOES_MEAT_BOWL);
                    output.accept(MASHED_POTATOES_FISH_BOWL);
                    output.accept(MASHED_POTATOES_POULTRY_BOWL);
                });
    }
}
