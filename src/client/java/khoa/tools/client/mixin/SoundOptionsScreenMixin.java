package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;

import khoa.tools.client.feature.soundvolume.SoundVolumeFeature;

/** While Sound Volume is on: a note at the top of Minecraft's Sound Options and the sliders of the ticked sound types greyed out. */
@Mixin(SoundOptionsScreen.class)
public abstract class SoundOptionsScreenMixin {
	@Inject(method = "addOptions", at = @At("HEAD"))
	private void khoatools$note(CallbackInfo ci) {
		if (SoundVolumeFeature.enabled()) {
			((OptionsSubScreenAccessor) (Object) this).khoatools$list()
				.addHeader(Component.literal("Sound Volume feature is turned on").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		}
	}

	@Inject(method = "addOptions", at = @At("TAIL"))
	private void khoatools$blackout(CallbackInfo ci) {
		if (!SoundVolumeFeature.enabled()) {
			return;
		}
		OptionsList list = ((OptionsSubScreenAccessor) (Object) this).khoatools$list();
		for (SoundSource source : SoundSource.values()) {
			if (!SoundVolumeFeature.overrides(source)) {
				continue; // not ticked: vanilla slider stays usable
			}
			AbstractWidget widget = list.findOption(Minecraft.getInstance().options.getSoundSourceOptionInstance(source));
			if (widget != null) {
				widget.active = false;
			}
		}
	}
}
