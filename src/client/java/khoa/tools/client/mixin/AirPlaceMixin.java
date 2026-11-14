package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;

import khoa.tools.client.feature.airplace.AirPlaceFeature;

/** Right click on nothing places the held block in the air (Air Place). */
@Mixin(Minecraft.class)
public abstract class AirPlaceMixin {
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void khoatools$airPlace(CallbackInfo ci) {
		if (AirPlaceFeature.onUse((Minecraft) (Object) this)) {
			ci.cancel();
		}
	}
}
