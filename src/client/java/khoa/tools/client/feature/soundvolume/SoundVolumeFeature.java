package khoa.tools.client.feature.soundvolume;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

import khoa.tools.KhoaTools;
import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.setting.Setting;

/**
 * Sound Volume (client side only): one 0-100 slider per sound category. While the card is on, these values replace the
 * volumes of Minecraft's own Sound Options (OptionsSoundVolumeMixin); those sliders are greyed out with a note
 * (SoundOptionsScreenMixin). Turning it off gives the vanilla values back, they are never modified.
 * A slider that was never touched follows the vanilla slider.
 */
public final class SoundVolumeFeature implements KhoaFeature {
	private static final String ID = "soundvolume";
	private static boolean on;

	private static String label(SoundSource source) {
		return switch (source) {
			case MASTER -> "Master Volume";
			case MUSIC -> "Music";
			case RECORDS -> "Jukebox / Note Blocks";
			case WEATHER -> "Weather";
			case BLOCKS -> "Blocks";
			case HOSTILE -> "Hostile Creatures";
			case NEUTRAL -> "Friendly Creatures";
			case PLAYERS -> "Players";
			case AMBIENT -> "Ambient / Environment";
			case VOICE -> "Voice / Speech";
			case UI -> "UI";
		};
	}

	private static String key(SoundSource source) {
		return source.name().toLowerCase(Locale.ROOT);
	}

	public static boolean enabled() {
		return on;
	}

	/** True when the feature is on AND this category's own "Override" tick box is on: only then it is overridden and locked. */
	public static boolean overrides(SoundSource source) {
		return on && KhoaConfig.getBool(ID, key(source) + "_on", false);
	}

	/** 0.0 - 1.0 for a category: the saved slider, or the vanilla slider when this one was never set. */
	public static float volume(SoundSource source) {
		return percent(source) / 100.0F;
	}

	private static int percent(SoundSource source) {
		int saved = KhoaConfig.getInt(ID, key(source), -1);
		if (saved >= 0) {
			return Math.min(100, saved);
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.options != null) {
			return Math.round(mc.options.getSoundSourceOptionInstance(source).get().floatValue() * 100.0F);
		}
		return 100;
	}

	/** Makes sounds that are already playing follow the new volumes right away. */
	private static void refresh() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getSoundManager() == null) {
			return;
		}
		try {
			for (SoundSource source : SoundSource.values()) {
				mc.getSoundManager().refreshCategoryVolume(source);
			}
		} catch (RuntimeException e) {
			KhoaTools.LOGGER.error("Sound Volume: could not refresh playing sounds", e);
		}
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Sound Volume";
	}

	@Override
	public String description() {
		return "Client side only. Click the card to turn it on/off. Each sound type has an Override tick box and a slider (0 = silent, 100 = full). "
			+ "Only the types whose Override box is ticked are controlled here and greyed out in Minecraft's Sound Options; the others are not touched. "
			+ "Turning the card off gives the normal volumes back.";
	}

	@Override
	public List<Setting> settings() {
		List<Setting> out = new ArrayList<>();
		for (SoundSource source : SoundSource.values()) {
			out.add(Setting.bool(key(source) + "_on", "Override " + label(source), "On = this feature controls this sound type and Minecraft's slider for it is locked. Off = untouched.",
				() -> KhoaConfig.getBool(ID, key(source) + "_on", false), value -> {
					KhoaConfig.put(ID, key(source) + "_on", value);
					refresh();
				}));
			out.add(Setting.slider(key(source), label(source), "0 = silent, 100 = full volume.", 0, 100, () -> percent(source), value -> {
				KhoaConfig.put(ID, key(source), Math.max(0, Math.min(100, value)));
				if (on) {
					refresh();
				}
			}));
		}
		return out;
	}

	@Override
	public boolean persistEnabled() {
		return true;
	}

	@Override
	public boolean isActive() {
		return on;
	}

	@Override
	public void activate(Minecraft mc) {
		on = !on;
		refresh();
		KhoaFeature.say(mc, "Sound Volume: " + (on ? "on (Minecraft's Sound Options are overridden)" : "off"));
	}
}
