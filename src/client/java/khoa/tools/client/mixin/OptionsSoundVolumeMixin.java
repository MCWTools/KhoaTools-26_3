package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Options;
import net.minecraft.sounds.SoundSource;

import khoa.tools.client.feature.soundvolume.SoundVolumeFeature;

/** Sound Volume: every volume the game reads (also getFinalSoundSourceVolume, which multiplies by master) comes from our sliders. */
@Mixin(Options.class)
public abstract class OptionsSoundVolumeMixin {
	@Inject(method = "getSoundSourceVolume", at = @At("HEAD"), cancellable = true)
	private void khoatools$soundVolume(SoundSource source, CallbackInfoReturnable<Float> cir) {
		if (SoundVolumeFeature.overrides(source)) {
			cir.setReturnValue(SoundVolumeFeature.volume(source));
		}
	}
}
