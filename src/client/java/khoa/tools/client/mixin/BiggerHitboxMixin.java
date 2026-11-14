package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;

import khoa.tools.client.feature.biggerhitbox.BiggerHitboxFeature;

/** Right click on the enlarged fake hitbox of a tiny block (Bigger Hitbox). */
@Mixin(Minecraft.class)
public abstract class BiggerHitboxMixin {
	@Shadow
	private int rightClickDelay;

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void khoatools$biggerHitbox(CallbackInfo ci) {
		if (BiggerHitboxFeature.onUse((Minecraft) (Object) this)) {
			rightClickDelay = 4; // same delay as a normal use, so holding the button does not place every tick
			ci.cancel();
		}
	}
}
