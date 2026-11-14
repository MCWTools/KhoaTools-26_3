package khoa.tools.client.feature.illegal;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.setting.Setting;

/** The "Mode" dropdown shared by the finder features: Box, Lines (a line from you to the target) or Box and Lines. Stored as an index in the feature's config section. */
public final class FinderMode {
	public static final int BOX = 0;
	public static final int LINES = 1;
	public static final int BOTH = 2;
	private static final List<String> NAMES = List.of("Box", "Lines", "Box and Lines");

	private FinderMode() {
	}

	public static Setting setting(String section) {
		return Setting.dropdown("mode", "Mode", "Box: overlay on the target. Lines: a line from you to the target. Box and Lines: both.", NAMES,
			() -> get(section), v -> KhoaConfig.put(section, "mode", v));
	}

	public static int get(String section) {
		return Math.floorMod(KhoaConfig.getInt(section, "mode", BOX), NAMES.size());
	}

	public static void cycle(String section) {
		KhoaConfig.put(section, "mode", (get(section) + 1) % NAMES.size());
	}

	public static String name(String section) {
		return NAMES.get(get(section));
	}

	public static boolean box(int mode) {
		return mode == BOX || mode == BOTH;
	}

	public static boolean lines(int mode) {
		return mode == LINES || mode == BOTH;
	}

	/** Where lines start: a little in front of and below your eyes, so they stay visible in first person. */
	public static Vec3 lineStart(Minecraft mc) {
		return mc.player.getEyePosition().add(mc.player.getLookAngle().scale(0.6)).add(0, -0.3, 0);
	}

	public static void line(Vec3 from, Vec3 to, int argb) {
		Gizmos.line(from, to, argb, 2.0F).setAlwaysOnTop();
	}
}
