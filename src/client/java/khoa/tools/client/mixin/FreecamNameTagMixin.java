package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;

import khoa.tools.client.feature.camera.CameraWatch;

/** Vanilla never draws the name tag of the camera entity (you); in freecam you see yourself from outside, so draw it. */
@Mixin(LivingEntityRenderer.class)
public abstract class FreecamNameTagMixin {
	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("HEAD"), cancellable = true)
	private void khoatools$ownNameTag(LivingEntity entity, double distanceToCameraSq, CallbackInfoReturnable<Boolean> cir) {
		Minecraft mc = Minecraft.getInstance();
		if (CameraWatch.isFree() && entity == mc.player) {
			cir.setReturnValue(!mc.gui.hud.isHidden());
		}
	}
}
