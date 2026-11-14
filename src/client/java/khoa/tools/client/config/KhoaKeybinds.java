package khoa.tools.client.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;

import khoa.tools.KhoaTools;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.client.feature.KhoaFeatures;
import khoa.tools.setting.Setting;

/**
 * Optional hotkey per feature (config/khoatools.json -> <id>.keybind = GLFW key code, -1 = None).
 * Pressing the key calls {@link KhoaFeature#activate} (same as clicking the card). Only fires with no screen open.
 */
public final class KhoaKeybinds {
	public static final int NONE = -1;

	private static final Map<String, Integer> KEYS = new HashMap<>();
	private static final Map<String, Boolean> WAS_DOWN = new HashMap<>();

	/** Small per-feature keys (Setting.Type.KEYBIND rows), collected once from every feature's settings(). */
	private static List<Setting> subKeys;
	private static final Map<Setting, KhoaFeature> SUB_OWNER = new HashMap<>();
	private static final Map<Setting, Boolean> SUB_WAS_DOWN = new HashMap<>();

	private KhoaKeybinds() {
	}

	/**
	 * Config row for one extra key of a feature, e.g. {@code KhoaKeybinds.setting("spawnerfinder", "path_key", "Pathfinding key", "Toggles pathfinding.", () -> ...)}.
	 * Stored as config/khoatools.json -> section.key; pressing it (no screen open) runs {@code onPress}. Just add the returned Setting to the
	 * feature's settings(): nothing else to register, and features that do not use it get no extra row.
	 */
	public static Setting setting(String section, String key, String label, String description, Runnable onPress) {
		return Setting.keybind(key, label, description, () -> KhoaConfig.getInt(section, key, NONE), v -> KhoaConfig.put(section, key, v), onPress);
	}

	private static List<Setting> subKeys() {
		if (subKeys == null) {
			List<Setting> list = new ArrayList<>();
			for (KhoaFeature feature : KhoaFeatures.all()) {
				try {
					for (Setting s : feature.settings()) {
						if (s.type == Setting.Type.KEYBIND) {
							list.add(s);
							SUB_OWNER.put(s, feature);
						}
					}
				} catch (RuntimeException e) {
					KhoaTools.LOGGER.error("Could not read the settings of {}", feature.id(), e);
				}
			}
			subKeys = list;
		}
		return subKeys;
	}

	public static int get(String featureId) {
		return KEYS.computeIfAbsent(featureId, id -> KhoaConfig.getInt(id, "keybind", NONE));
	}

	public static void set(String featureId, int keyCode) {
		KEYS.put(featureId, keyCode);
		KhoaConfig.put(featureId, "keybind", keyCode);
	}

	/** Text of the keybind button without the brackets, e.g. "None" or "R". */
	public static String keyName(int keyCode) {
		if (keyCode == NONE) return "None";
		return InputConstants.Type.KEYBOARD.getOrCreate(keyCode).getDisplayName().getString();
	}

	public static void tick(Minecraft mc) {
		if (mc.getWindow() == null) return;
		boolean canFire = mc.gui.screen() == null && mc.player != null && mc.isWindowActive();
		for (Setting s : subKeys()) {
			int key = s.getInt();
			if (key == NONE || KhoaCheat.isHidden(SUB_OWNER.get(s))) continue;
			boolean down = InputConstants.isKeyDown(key);
			boolean was = SUB_WAS_DOWN.getOrDefault(s, false);
			SUB_WAS_DOWN.put(s, down);
			if (down && !was && canFire) {
				try {
					s.run();
				} catch (RuntimeException e) {
					KhoaTools.LOGGER.error("Key action {} failed", s.key, e);
				}
			}
		}
		for (KhoaFeature feature : KhoaFeatures.all()) {
			int key = get(feature.id());
			if (key == NONE || KhoaCheat.isHidden(feature)) continue;
			boolean down = InputConstants.isKeyDown(key);
			boolean was = WAS_DOWN.getOrDefault(feature.id(), false);
			WAS_DOWN.put(feature.id(), down);
			if (down && !was && canFire) {
				try {
					feature.activate(mc);
				} catch (RuntimeException e) {
					KhoaTools.LOGGER.error("Feature {} failed from keybind", feature.id(), e);
				}
			}
		}
	}
}
