package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.player.LocalPlayer;

import khoa.tools.client.feature.camlock.CamLockFeature;

/** Cam Lock: puts the locked angle back after every tick (knockback, teleports, server rotation packets). */
@Mixin(LocalPlayer.class)
public abstract class CamLockTickMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void khoatools$camLockTick(CallbackInfo ci) {
		if (CamLockFeature.isLocked()) {
			LocalPlayer self = (LocalPlayer) (Object) this;
			float yaw = CamLockFeature.yaw();
			float pitch = CamLockFeature.pitch();
			self.setYRot(yaw);
			self.setXRot(pitch);
			self.yRotO = yaw;
			self.xRotO = pitch;
			self.yHeadRot = yaw;
			self.yHeadRotO = yaw;
		}
	}
}
