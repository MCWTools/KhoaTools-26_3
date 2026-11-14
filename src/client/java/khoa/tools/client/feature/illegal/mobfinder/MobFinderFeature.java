package khoa.tools.client.feature.illegal.mobfinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.config.KhoaKeybinds;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.client.feature.illegal.FinderMode;
import khoa.tools.client.feature.illegal.spawnerfinder.SpawnerMobs;
import khoa.tools.client.gui.picker.PickFilter;
import khoa.tools.setting.Setting;

/**
 * Mob Finder: pick entity types (mobs, other players, items, armor stands... anything in the entity registry) and the loaded ones get a box
 * on their hitbox and/or a line from you, through walls. Each entity type has its own colour. You are never marked. Only the nearest
 * "Overlay limit" entities are drawn.
 */
public final class MobFinderFeature implements KhoaFeature {
	private static final String ID = "mobfinder";
	private static final PickFilter PICKED = new PickFilter(ID, "entities");

	private static boolean on;
	private static List<String> source;
	private static Set<String> ids = Set.of();

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Mob Finder";
	}

	@Override
	public String description() {
		return "Pick entities (mobs, players, special entities) in Entities to find, turn the card on and they get a hitbox box and/or a line to them through walls.";
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
		KhoaFeature.say(mc, "Mob Finder: " + (on ? "on" : "off"));
	}

	@Override
	public List<Setting> settings() {
		return List.of(
			PICKED.setting("Entities to find", "Entity types that get marked. Empty = nothing is shown. Includes players and special entities.", List.of("entity")),
			FinderMode.setting(ID),
			KhoaKeybinds.setting(ID, "mode_key", "Mode key", "Press to switch Box / Lines / Box and Lines.", () -> {
				FinderMode.cycle(ID);
				KhoaFeature.say(Minecraft.getInstance(), "Mob Finder mode: " + FinderMode.name(ID));
			}),
			Setting.slider("limit", "Overlay limit", "Most entities marked at once; the farthest ones are hidden.", 1, 1000,
				() -> KhoaConfig.getInt(ID, "limit", 200), v -> KhoaConfig.put(ID, "limit", v)),
			Setting.slider("distance", "Max distance (0 = no limit)", "Entities farther than this many blocks are not marked.", 0, 256,
				() -> KhoaConfig.getInt(ID, "distance", 0), v -> KhoaConfig.put(ID, "distance", v)));
	}

	@Override
	public void onInit() {
		LevelRenderEvents.END_EXTRACTION.register(context -> draw());
	}

	private static Set<String> ids() {
		List<String> keys = PICKED.get();
		if (keys != source) {
			source = keys;
			Set<String> set = new HashSet<>();
			for (String key : keys) {
				int colon = key.indexOf(':');
				set.add(colon < 0 ? key : key.substring(colon + 1));
			}
			ids = set;
		}
		return ids;
	}

	private static void draw() {
		Minecraft mc = Minecraft.getInstance();
		if (!on || mc.level == null || mc.player == null) {
			return;
		}
		Set<String> wanted = ids();
		if (wanted.isEmpty()) {
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		int maxDist = KhoaConfig.getInt(ID, "distance", 0);
		double maxSq = maxDist > 0 ? (double) maxDist * maxDist : Double.MAX_VALUE;
		List<Entity> hits = new ArrayList<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e == mc.player || e.isRemoved() || !wanted.contains(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString())) {
				continue;
			}
			if (e.distanceToSqr(eye) <= maxSq) {
				hits.add(e);
			}
		}
		int limit = Math.max(1, KhoaConfig.getInt(ID, "limit", 200));
		if (hits.size() > limit) {
			hits.sort(Comparator.comparingDouble(e -> e.distanceToSqr(eye)));
			hits = hits.subList(0, limit);
		}
		int mode = FinderMode.get(ID);
		Vec3 from = FinderMode.lineStart(mc);
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		for (Entity e : hits) {
			int color = 0xFF000000 | SpawnerMobs.colorOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
			Vec3 shift = e.getPosition(partial).subtract(e.position()); // smooth movement between ticks
			AABB box = e.getBoundingBox().move(shift);
			if (FinderMode.box(mode)) {
				Gizmos.cuboid(box, GizmoStyle.strokeAndFill(color, 2.0F, (color & 0xFFFFFF) | 0x28000000)).setAlwaysOnTop();
			}
			if (FinderMode.lines(mode)) {
				FinderMode.line(from, box.getCenter(), color);
			}
		}
	}
}
