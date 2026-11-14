package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import khoa.tools.client.feature.totemtweaks.TotemTweaksFeature;

/** Totem Tweaks (26.3): the pop animation item is fixed when the render state is created, so swap it there. */
@Mixin(PlayerRenderState.ItemActivationRenderState.class)
public abstract class ItemActivationRenderStateMixin {
	@ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
	private static ItemStack khoa$totemItem(ItemStack stack) {
		boolean totem = TotemTweaksFeature.enabled() && stack.is(Items.TOTEM_OF_UNDYING);
		TotemTweaksFeature.popping = totem;
		if (totem) {
			ItemStack replacement = TotemTweaksFeature.replacement();
			if (replacement != null) {
				return replacement;
			}
		}
		return stack;
	}
}