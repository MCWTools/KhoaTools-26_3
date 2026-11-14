package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;

import khoa.tools.client.feature.waypoints.WaypointsFeature;

/** While the Waypoints screen waits for a block to be selected (shift + right click), clicks must not place, use or break anything. */
@Mixin(Minecraft.class)
public abstract class WaypointPickMixin {
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void khoatools$pickUse(CallbackInfo ci) {
		if (WaypointsFeature.onUse((Minecraft) (Object) this)) {
			ci.cancel();
		}
	}

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void khoatools$pickAttack(CallbackInfoReturnable<Boolean> cir) {
		if (WaypointsFeature.onAttack()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void khoatools$pickContinueAttack(boolean leftClick, CallbackInfo ci) {
		if (WaypointsFeature.onAttack()) {
			ci.cancel();
		}
	}
}
