package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import khoa.tools.client.feature.freecam.FreecamReach;

/**
 * Freecam "through wall" options. Both change only what the player's own aim hits; the freecam position is never used.
 * Without them vanilla picking is untouched, so clicks (manual or Auto Clicker) go where the player's head looks.
 */
@Mixin(Minecraft.class)
public abstract class FreecamMinecraftMixin {
	@Shadow public HitResult hitResult;
	@Shadow public Entity crosshairPickEntity;

	@Unique private HitResult khoatools$hitBeforeUse;

	@Inject(method = "pick", at = @At("TAIL"))
	private void khoatools$entityThroughWall(float partialTicks, CallbackInfo ci) {
		if (hitResult instanceof EntityHitResult) return;
		EntityHitResult hit = FreecamReach.entity((Minecraft) (Object) this, partialTicks);
		if (hit != null) {
			hitResult = hit;
			crosshairPickEntity = hit.getEntity();
		}
	}

	/** Only right clicks are redirected to a hidden block, so mining still hits the block you look at. */
	@Inject(method = "startUseItem", at = @At("HEAD"))
	private void khoatools$blockThroughWall(CallbackInfo ci) {
		khoatools$hitBeforeUse = null;
		if (hitResult instanceof EntityHitResult) return;
		BlockHitResult hit = FreecamReach.block((Minecraft) (Object) this);
		if (hit != null) {
			khoatools$hitBeforeUse = hitResult;
			hitResult = hit;
		}
	}

	@Inject(method = "startUseItem", at = @At("RETURN"))
	private void khoatools$restoreHit(CallbackInfo ci) {
		if (khoatools$hitBeforeUse != null) {
			hitResult = khoatools$hitBeforeUse;
			khoatools$hitBeforeUse = null;
		}
	}
}
