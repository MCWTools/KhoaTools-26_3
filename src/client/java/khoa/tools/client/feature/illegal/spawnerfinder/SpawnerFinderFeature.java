package khoa.tools.client.feature.illegal.spawnerfinder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.Minecraft;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.config.KhoaKeybinds;
import khoa.tools.client.feature.illegal.FinderMode;
import khoa.tools.client.feature.illegal.StarePath;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.client.gui.picker.PickFilter;
import khoa.tools.setting.Setting;

/**
 * Spawner Finder (ported from the SpawnerFinder mod): highlights spawners and trial spawners through walls and draws a safe path to the
 * one you stare at.
 * <ul>
 * <li>Tile on every face turned to you: normal spawner = dark grey, trial spawner = copper top half + dark grey bottom half; a square
 * (75 %) in the middle has the colour of the mob inside.</li>
 * <li>Picker (Spawners): choose which mobs' spawners / trial spawners are highlighted; empty list = all of them. The "Vanilla" tick in
 * the picker only limits what the picker lists (vanilla spawner mobs, or every mob for servers with custom spawners).</li>
 * <li>Pathfinding: stare at a highlighted spawner for a few seconds (through walls, any distance) and the path is drawn.</li>
 * </ul>
 */
public final class SpawnerFinderFeature implements KhoaFeature {
	private static final String ID = "spawnerfinder";
	private static final PickFilter PICKED = new PickFilter(ID, "picked");

	private static boolean on;

	private static List<SpawnerScanner.Info> positionsFrom;
	private static List<net.minecraft.core.BlockPos> positionsCache = List.of();

	static final StarePath PATH = new StarePath("Spawner", () -> on && pathfinding(), SpawnerFinderFeature::stareSeconds,
		SpawnerFinderFeature::positions, pos -> {
			for (SpawnerScanner.Info s : SpawnerScanner.found()) {
				if (s.pos().equals(pos)) {
					return s.type() == null ? "spawner" : SpawnerMobs.name(s.type());
				}
			}
			return "spawner";
		}, true);

	/** Positions of the highlighted spawners (the targets you can stare at). */
	private static synchronized List<net.minecraft.core.BlockPos> positions() {
		List<SpawnerScanner.Info> list = filtered();
		if (list != positionsFrom) {
			positionsFrom = list;
			List<net.minecraft.core.BlockPos> out = new ArrayList<>(list.size());
			for (SpawnerScanner.Info s : list) {
				out.add(s.pos());
			}
			positionsCache = out;
		}
		return positionsCache;
	}

	private static List<String> pickedSource;
	private static Set<String> pickedSet = Set.of();
	private static List<SpawnerScanner.Info> filteredFrom;
	private static List<String> filteredPicks;
	private static List<SpawnerScanner.Info> filteredCache = List.of();

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Spawner Finder";
	}

	@Override
	public String description() {
		return "Highlights spawners (dark grey tile) and trial spawners (copper + dark grey tile) through walls, with a square in the colour of the mob inside. "
			+ "Pick which mobs to show in Spawners (empty = all). With Pathfinding on, look at a spawner for a few seconds to see a safe path to it. "
			+ "Click the card to turn it on/off.";
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
		if (!on) {
			PATH.clear();
			SpawnerScanner.clear();
		}
		KhoaFeature.say(mc, "Spawner Finder: " + (on ? "on" : "off"));
	}

	@Override
	public List<Setting> settings() {
		return List.of(
			Setting.itemList("picked", "Spawners", "Which spawners and trial spawners are highlighted. Empty = every spawner.", List.of("spawner"),
				PICKED::get, PICKED::set)
				.pickerToggle("Vanilla", "On: the picker lists only the mobs vanilla has spawners for. Off: every mob in the game (for servers with custom spawners).",
					SpawnerFinderFeature::vanillaPicker, v -> KhoaConfig.put(ID, "vanilla_picker", v)),
			FinderMode.setting(ID),
			Setting.bool("pathfinding", "Pathfinding", "Look at a highlighted spawner for a few seconds to draw the safe path to it (green = walkable, orange = needs digging, red boxes = blocks to mine).",
				SpawnerFinderFeature::pathfinding, v -> {
					KhoaConfig.put(ID, "pathfinding", v);
					if (!v) {
						PATH.clear();
					}
				}),
			KhoaKeybinds.setting(ID, "path_key", "Pathfinding key", "Press to turn Pathfinding on/off without opening the menu.", () -> {
				KhoaConfig.put(ID, "pathfinding", !pathfinding());
				if (!pathfinding()) {
					PATH.clear();
				}
				Minecraft mc = Minecraft.getInstance();
				KhoaFeature.say(mc, "Spawner Finder pathfinding: " + (pathfinding() ? "on" : "off"));
			}),
			Setting.integer("stare_seconds", "Path after looking (seconds)", "How long you must look at a spawner before its path is drawn.", 1, 60,
				SpawnerFinderFeature::stareSeconds, v -> KhoaConfig.put(ID, "stare_seconds", v)),
			Setting.integer("max_count", "Max highlighted (0 = no limit)", "Only the nearest N spawners get a tile.", 0, 5000,
				SpawnerFinderFeature::maxCount, v -> KhoaConfig.put(ID, "max_count", v)),
			Setting.integer("max_distance", "Max distance (0 = whole scan range)", "Only spawners closer than this many blocks get a tile. Spawners are scanned within about 160 blocks.", 0, 200,
				SpawnerFinderFeature::maxDistance, v -> KhoaConfig.put(ID, "max_distance", v)));
	}

	@Override
	public void onInit() {
		SpawnerOverlay.register();
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (on && mc.level != null && mc.player != null) {
				SpawnerScanner.scanIfNeeded(mc);
			}
			PATH.onClientTick(mc);
		});
	}

	// ---- settings (config/khoatools.json, section "spawnerfinder") ----

	static boolean isOn() {
		return on;
	}

	static boolean vanillaPicker() {
		return KhoaConfig.getBool(ID, "vanilla_picker", true);
	}

	static boolean pathfinding() {
		return KhoaConfig.getBool(ID, "pathfinding", true);
	}

	static int stareSeconds() {
		return KhoaConfig.getInt(ID, "stare_seconds", 5);
	}

	static int maxCount() {
		return KhoaConfig.getInt(ID, "max_count", 300);
	}

	static int maxDistance() {
		return KhoaConfig.getInt(ID, "max_distance", 0);
	}

	/** Spawners that pass the picked list (everything when the list is empty), nearest first. Cached until the scan or the list changes. */
	static synchronized List<SpawnerScanner.Info> filtered() {
		List<String> picks = PICKED.get();
		List<SpawnerScanner.Info> found = SpawnerScanner.found();
		if (found == filteredFrom && picks == filteredPicks) {
			return filteredCache;
		}
		if (picks != pickedSource) {
			pickedSource = picks;
			pickedSet = new HashSet<>(picks);
		}
		List<SpawnerScanner.Info> out;
		if (pickedSet.isEmpty()) {
			out = found;
		} else {
			out = new ArrayList<>();
			for (SpawnerScanner.Info s : found) {
				if (pickedSet.contains(s.pickKey())) {
					out.add(s);
				}
			}
		}
		filteredFrom = found;
		filteredPicks = picks;
		filteredCache = out;
		return out;
	}
}
