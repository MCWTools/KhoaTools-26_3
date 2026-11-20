package khoa.tools.client.feature.farlod;

import java.util.List;

import net.minecraft.client.Minecraft;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.setting.Setting;

/**
 * Far Mob LOD: living entities that look small (far for their size) are drawn without their layers (armor, enchant glint, held items, heads...),
 * only the plain body is drawn. A big army stays visible but costs far fewer draw calls. Client only, no effect on the game.
 */
public final class FarLodFeature implements KhoaFeature {
	private static boolean active;

	/** Distance in blocks at which a zombie sized mob (1.8 blocks tall) loses its layers at scale 100%. */
	private static final double BASE_DISTANCE = 32.0;
	private static final double REFERENCE_SIZE = 1.8;

	/**
	 * True when the mob looks small enough on screen to be drawn plain. It depends on the distance and on the mob's own size (what you
	 * actually see): a ghast or dragon keeps its details much farther away than a zombie, a bat loses them sooner. The scale setting
	 * (50-200 %) moves the whole threshold: 50 % = lower quality, details disappear at half the distance, 200 % = they stay twice as far.
	 *
	 * @param distanceSq squared distance from the camera in blocks
	 * @param width / height bounding box of the mob in blocks
	 */
	public static boolean isFar(double distanceSq, float width, float height) {
		if (!active) {
			return false;
		}
		double size = Math.max(0.3, Math.max(width, height));
		double scale = KhoaConfig.getInt("farlod", "scale", 100) / 100.0;
		double limit = BASE_DISTANCE * scale * (size / REFERENCE_SIZE);
		return distanceSq > limit * limit;
	}

	@Override
	public String id() {
		return "farlod";
	}

	@Override
	public String displayName() {
		return "Far Mob LOD";
	}

	@Override
	public String description() {
		return "Far or small looking mobs are drawn plain (no armor, glint, held items). For huge armies.";
	}

	@Override
	public List<Setting> settings() {
		return List.of(Setting.slider("scale", "LOD scale (%)",
			"100 = a zombie sized mob is drawn plain beyond 32 blocks (bigger mobs keep details farther, smaller ones lose them sooner). 50 = lower quality, details vanish at half the distance. 200 = details stay twice as far.",
			50, 200, () -> KhoaConfig.getInt(id(), "scale", 100), v -> KhoaConfig.put(id(), "scale", v)));
	}

	@Override
	public boolean persistEnabled() {
		return true;
	}

	@Override
	public boolean isActive() {
		return active;
	}

	@Override
	public void activate(Minecraft mc) {
		active = !active;
		KhoaFeature.say(mc, "Far Mob LOD: " + (active ? "on" : "off") + " (scale " + KhoaConfig.getInt(id(), "scale", 100) + "%)");
	}
}
