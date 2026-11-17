package pl.sofron.terralab.soil.mixin;

import net.minecraft.core.NonNullList;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CraftingRecipe.class)
public interface FoodCraftingMixin {
    @Inject(method = "defaultCraftingReminder", at = @At("RETURN"), cancellable = true)
    private static void sofronpack$keepAxe(CraftingInput input,
                                             CallbackInfoReturnable<NonNullList<ItemStack>> cir) {
        if (input.width() != 3 || input.height() != 3) return;
        ItemStack tool = input.getItem(4);
        if (!tool.is(ItemTags.AXES)) return;
        int category = 0;
        for (int i = 0; i < 9; i++) {
            if (i == 4) continue;
            ItemStack stack = input.getItem(i);
            int current;
            if (stack.is(Items.BEEF) || stack.is(Items.PORKCHOP)
                    || stack.is(Items.MUTTON) || stack.is(Items.RABBIT)) current = 1;
            else if (stack.is(Items.COD) || stack.is(Items.SALMON)
                    || stack.is(Items.TROPICAL_FISH) || stack.is(Items.PUFFERFISH)) current = 2;
            else if (stack.is(Items.CHICKEN)) current = 3;
            else return;
            if (category != 0 && category != current) return;
            category = current;
        }
        NonNullList<ItemStack> remainder = cir.getReturnValue();
        if (remainder == null || remainder.size() != 9) return;
        remainder.set(4, tool.copyWithCount(1));
        cir.setReturnValue(remainder);
    }
}
