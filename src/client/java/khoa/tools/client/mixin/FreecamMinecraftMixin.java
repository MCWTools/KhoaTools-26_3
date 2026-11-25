package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import khoa.tools.client.feature.freecam.FreecamFeature;
import khoa.tools.client.feature.freecam.FreecamReach;

/**
 * Freecam with a "through wall" option on: the crosshair target (selection box, attack, use, mining, Auto Clicker) is what the
 * freecam aims at. Otherwise ("Use player head aim") vanilla picking is untouched, so everything goes where the player looks.
 */
@Mixin(Minecraft.class)
public abstract class FreecamMinecraftMixin {
	@Shadow public HitResult hitResult;
	@Shadow public Entity crosshairPickEntity;

	@Inject(method = "pick", at = @At("TAIL"))
	private void khoatools$cameraAim(float partialTicks, CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		if (!FreecamFeature.usesCameraAim() || mc.player == null || mc.level == null) return;
		hitResult = FreecamReach.cameraAim(mc, partialTicks);
		crosshairPickEntity = hitResult instanceof EntityHitResult entityHit ? entityHit.getEntity() : null;
	}
}
