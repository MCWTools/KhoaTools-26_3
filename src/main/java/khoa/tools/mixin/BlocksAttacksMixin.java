package khoa.tools.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;

import khoa.tools.command.ShieldBreakCdFeature;

/** Applies the custom_data {@code shield_break_cd} tag when a shield is disabled (e.g. by an axe). */
@Mixin(BlocksAttacks.class)
public abstract class BlocksAttacksMixin {
	@Inject(method = "disable", at = @At("HEAD"), cancellable = true)
	private void khoa$shieldBreakCd(ServerLevel level, LivingEntity entity, float seconds, ItemStack stack, CallbackInfo ci) {
		Integer ticks = ShieldBreakCdFeature.get(stack);
		if (ticks == null) {
			return;
		}
		BlocksAttacks self = (BlocksAttacks) (Object) this;
		if (entity instanceof Player player && ticks > 0) {
			player.getCooldowns().addCooldown(stack, ticks);
		}
		entity.stopUsingItem();
		self.disableSound().ifPresent(sound -> level.playSound(null, entity.getX(), entity.getY(), entity.getZ(), sound,
			entity.getSoundSource(), 0.8F, 0.8F + level.getRandom().nextFloat() * 0.4F));
		ci.cancel();
	}
}
