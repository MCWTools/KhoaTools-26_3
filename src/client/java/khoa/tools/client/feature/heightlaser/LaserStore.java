package khoa.tools.client.feature.heightlaser;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;

import khoa.api.util.ServerKey;
import khoa.tools.client.config.KhoaConfig;

/** The lasers of the current world (server + dimension), saved in config/khoatools.json under "heightlaser". */
final class LaserStore {
	private static final String SECTION = "heightlaser";

	private static final List<Laser> LASERS = new ArrayList<>();
	private static String loadedKey = "";

	private LaserStore() {
	}

	private static String key(Minecraft mc) {
		if (mc.level == null) {
			return "";
		}
		return ServerKey.id(mc) + "|" + mc.level.dimension().identifier();
	}

	/** Reloads when the world / dimension changed. */
	static void sync(Minecraft mc) {
		String k = key(mc);
		if (k.equals(loadedKey)) {
			return;
		}
		loadedKey = k;
		LASERS.clear();
		LaserBeams.clear();
		if (!k.isEmpty()) {
			for (String s : KhoaConfig.getStringList(SECTION, k)) {
				Laser l = Laser.deserialize(s);
				if (l != null) {
					LASERS.add(l);
				}
			}
		}
	}

	static List<Laser> all() {
		return LASERS;
	}

	static void add(Laser l) {
		LASERS.add(l);
		save();
	}

	static void remove(Laser l) {
		LASERS.remove(l);
		save();
	}

	static void save() {
		if (loadedKey.isEmpty()) {
			return;
		}
		List<String> out = new ArrayList<>();
		for (Laser l : LASERS) {
			out.add(l.serialize());
		}
		KhoaConfig.putStringList(SECTION, loadedKey, out);
	}
}
