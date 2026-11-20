package khoa.tools.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.Mob;

import khoa.tools.command.FarMobAiFeature;

/** /khoatools farmobai: far mobs skip most AI steps. */
@Mixin(Mob.class)
public abstract class MobAiMixin {
	@Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
	private void khoa$farMobAi(CallbackInfo ci) {
		if (FarMobAiFeature.shouldSkip((Mob) (Object) this)) {
			ci.cancel();
		}
	}
}
