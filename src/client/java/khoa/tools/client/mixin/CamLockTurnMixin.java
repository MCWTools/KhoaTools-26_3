package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

import khoa.tools.client.feature.camera.CameraWatch;
import khoa.tools.client.feature.camlock.CamLockFeature;

/** Cam Lock: mouse movement does not turn the local player. Camera Watch has priority: while watching, the mouse turns the watch camera (CameraWatchTurnMixin) and the player stays at the locked angle via CamLockTickMixin. */
@Mixin(Entity.class)
public abstract class CamLockTurnMixin {
	@Inject(method = "turn", at = @At("HEAD"), cancellable = true)
	private void khoatools$camLockTurn(double yRot, double xRot, CallbackInfo ci) {
		if (CamLockFeature.isLocked() && !CameraWatch.isWatching() && (Object) this instanceof LocalPlayer player) {
			player.setYRot(CamLockFeature.yaw());
			player.setXRot(CamLockFeature.pitch());
			ci.cancel();
		}
	}
}
