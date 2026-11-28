package khoa.tools.client.feature.lightlevel;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.gui.KhoaToolsScreen;

/**
 * In-process test of "Optimal placement" (dev only: {@code gradlew runClient -Plighttest}). Builds a stone platform high
 * above spawn in the dev world, places torches and obstacles with commands, reads the plan and checks it against the
 * game's own light. Writes run/config/khoatools-lighttest.log and run/screenshots/lighttest-*.png, then closes the game.
 */
final class LightPlanSelfTest {
	private record Step(String name, Runnable action, BooleanSupplier ready, Supplier<String> check) {
	}

	private static final boolean AUTO = System.getProperty("khoatools.lighttest") != null;
	private static final String ID = "lightlevel";
	private static final int FLOOR = 199;
	private static final int HALF = 24;
	private static final int MIN_Y = 150;
	private static final int MAX_Y = 230;
	private static final int TIMEOUT = 900;
	/** The lower bound needs a flood from every candidate: only worth it for small areas. */
	private static final int BOUND_LIMIT = 6000;
	private static final List<Step> STEPS = new ArrayList<>();
	private static boolean started;
	private static boolean acted;
	private static int index;
	private static int ticks;
	private static int idleTicks;
	private static int worldTicks;
	private static int passes;
	private static int failures;
	private static Path log;
	private static LightLevelOverlayFeature feature;
	private static int cx;
	private static int cz;
	private static int roundsBefore;
	private static List<LightPlan.Spot> remembered = List.of();
	private static int oldFov;

	private LightPlanSelfTest() {
	}

	static void tick(Minecraft mc, LightLevelOverlayFeature overlay) {
		if (!AUTO) {
			return;
		}
		if (!started) {
			mc.options.pauseOnLostFocus = false;
			if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen) {
				mc.gui.setScreen(null);
			}
			worldTicks = mc.player != null && mc.level != null && mc.gui.screen() == null ? worldTicks + 1 : 0;
			if (worldTicks > 100) {
				started = true;
				feature = overlay;
				log = FabricLoader.getInstance().getConfigDir().resolve("khoatools-lighttest.log");
				try {
					Files.writeString(log, "", StandardCharsets.UTF_8);
				} catch (IOException ignored) {
					// best effort
				}
				build(mc);
				write("started, " + STEPS.size() + " steps");
			}
			return;
		}
		if (index >= STEPS.size()) {
			write("DONE: " + passes + " passed, " + failures + " failed");
			started = false;
			worldTicks = Integer.MIN_VALUE;
			mc.stop();
			return;
		}
		Step s = STEPS.get(index);
		if (!acted) {
			write("step " + (index + 1) + ": " + s.name());
			roundsBefore = feature.planner().rounds();
			try {
				s.action().run();
			} catch (Throwable t) {
				fail("exception " + t);
				index++;
				return;
			}
			acted = true;
			ticks = 0;
			idleTicks = 0;
			return;
		}
		ticks++;
		idleTicks = s.ready() == null || s.ready().getAsBoolean() ? idleTicks + 1 : 0;
		if ((ticks < 10 || idleTicks < 12) && ticks < TIMEOUT) {
			return;
		}
		acted = false;
		index++;
		String result;
		try {
			result = ticks >= TIMEOUT ? "timed out" : s.check() == null ? null : s.check().get();
		} catch (Throwable t) {
			result = "exception " + t;
		}
		if (result == null) {
			passes++;
			write("  ok");
		} else {
			fail(result);
		}
	}

	private static void fail(String message) {
		failures++;
		write("  FAIL " + message);
	}

	private static void write(String line) {
		System.out.println("[KhoaTools lighttest] " + line);
		if (log != null) {
			try {
				Files.writeString(log, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException ignored) {
				// best effort
			}
		}
	}

	private static String expect(boolean ok, String message) {
		return ok ? null : message;
	}

	private static void add(String name, Runnable action, BooleanSupplier ready, Supplier<String> check) {
		STEPS.add(new Step(name, action, ready, check));
	}

	/** A step that ends once the planner has finished at least one new round and has nothing left to do. */
	private static void planned(String name, Runnable action, Supplier<String> check) {
		add(name, action, () -> feature.idle() && feature.planner().rounds() > roundsBefore, check);
	}

	private static void cmd(Minecraft mc, String command) {
		mc.player.connection.sendCommand(command);
	}

	private static void set(Minecraft mc, int x, int y, int z, String block) {
		cmd(mc, "setblock " + x + " " + y + " " + z + " " + block);
	}

	private static void set(Minecraft mc, BlockPos p, String block) {
		set(mc, p.getX(), p.getY(), p.getZ(), block);
	}

	/** Dark platform again: floor of stone, six blocks of air above it. */
	private static void wipe(Minecraft mc) {
		cmd(mc, "fill " + (cx - HALF) + " " + (FLOOR + 1) + " " + (cz - HALF) + " " + (cx + HALF) + " " + (FLOOR + 6) + " " + (cz + HALF) + " air");
		cmd(mc, "fill " + (cx - HALF) + " " + FLOOR + " " + (cz - HALF) + " " + (cx + HALF) + " " + FLOOR + " " + (cz + HALF) + " stone");
	}

	private static void settings(int threshold, int source) {
		KhoaConfig.put(ID, "level", threshold);
		KhoaConfig.put(ID, "plan_source", source);
	}

	private static List<LightPlan.Spot> spots() {
		return feature.planner().state().spots();
	}

	private static LightScan.Snapshot snapshot(Minecraft mc) {
		LightPlan.State state = feature.planner().state();
		return LightScan.scan(LightScan.copy(mc.level, state.area()), state.settings());
	}

	private static Set<Long> red(Minecraft mc) {
		Set<Long> out = new HashSet<>();
		for (long cell : snapshot(mc).dark()) {
			out.add(cell);
		}
		return out;
	}

	/** The test's own count, straight from the world and its light engine, over the heights of the platform. */
	private static String sameAsWorld(Minecraft mc) {
		LightPlan.State state = feature.planner().state();
		LightScan.Area area = state.area();
		Set<Long> world = new HashSet<>();
		int low = Math.max(area.minY(), FLOOR - 4);
		int high = Math.min(area.maxY(), FLOOR + 10);
		for (int x = area.minX(); x <= area.maxX(); x++) {
			for (int z = area.minZ(); z <= area.maxZ(); z++) {
				for (int y = low; y <= high; y++) {
					BlockPos cell = new BlockPos(x, y, z);
					BlockState above = mc.level.getBlockState(cell);
					BlockState floor = mc.level.getBlockState(cell.below());
					boolean free = above.isAir() || above.getCollisionShape(mc.level, cell).isEmpty() && above.getFluidState().isEmpty();
					if (!floor.isAir() && free && floor.isFaceSturdy(mc.level, cell.below(), Direction.UP)
						&& mc.level.getBrightness(LightLayer.BLOCK, cell) <= state.settings().threshold()) {
						world.add(cell.asLong());
					}
				}
			}
		}
		Set<Long> scanned = red(mc);
		scanned.removeIf(cell -> BlockPos.getY(cell) < low || BlockPos.getY(cell) > high);
		return expect(world.equals(scanned), "scan and world disagree: scan " + scanned.size() + ", world " + world.size());
	}

	/** Every red surface is in some planned spot's list (or cannot be lit), no spot is pointless; logs a lower bound. */
	private static String checkPlan(Minecraft mc, String label) {
		LightPlan.State state = feature.planner().state();
		Set<Long> dark = red(mc);
		Set<Long> covered = new HashSet<>();
		for (LightPlan.Spot spot : state.spots()) {
			for (long cell : spot.covers()) {
				covered.add(cell);
			}
		}
		int missing = 0;
		for (long cell : dark) {
			if (!covered.contains(cell)) {
				missing++;
			}
		}
		java.util.Map<Long, Integer> lit = new java.util.HashMap<>();
		for (LightPlan.Spot spot : state.spots()) {
			for (long cell : spot.covers()) {
				lit.merge(cell, 1, Integer::sum);
			}
		}
		int redundant = 0;
		for (LightPlan.Spot spot : state.spots()) {
			boolean needed = false;
			for (long cell : spot.covers()) {
				needed |= dark.contains(cell) && lit.get(cell) == 1;
			}
			if (!needed) {
				redundant++;
			}
		}
		write("  [" + label + "] red " + dark.size() + ", planned " + state.spots().size()
			+ (dark.size() <= BOUND_LIMIT ? ", lower bound " + lowerBound(mc, dark, label) : "") + ", cannot be lit " + state.uncoverable() + " ("
			+ feature.timings() + ")");
		String world = sameAsWorld(mc);
		if (world != null) {
			return world;
		}
		return expect(missing == state.uncoverable() && redundant == 0,
			"red not covered by the plan: " + missing + " (cannot be lit " + state.uncoverable() + "), redundant spots " + redundant);
	}

	/** Dark cells no two of which one source could light together: each needs its own, so this many are needed at least. */
	private static int lowerBound(Minecraft mc, Set<Long> dark, String label) {
		LightPlan.State state = feature.planner().state();
		LightScan.Snapshot snapshot = snapshot(mc);
		LightPlanner.Flood flood = new LightPlanner.Flood(snapshot);
		java.util.Map<Integer, BitSet> by = new java.util.HashMap<>();
		int[] candidates = snapshot.candidates();
		for (int i = 0; i < candidates.length; i++) {
			flood.run(candidates[i], state.settings().emission(), state.settings().threshold() + 1);
			for (int k = 0; k < flood.reachedCount; k++) {
				if (dark.contains(snapshot.cell(flood.reached[k]))) {
					by.computeIfAbsent(flood.reached[k], c -> new BitSet()).set(i);
				}
			}
		}
		if (label.startsWith("min ")) {
			dump(label, state.spots().size(), by.values(), candidates.length);
		}
		List<BitSet> sets = new ArrayList<>(by.values());
		sets.sort(Comparator.comparingInt(BitSet::cardinality));
		BitSet used = new BitSet();
		int bound = 0;
		for (BitSet s : sets) {
			if (!s.intersects(used)) {
				used.or(s);
				bound++;
			}
		}
		return bound;
	}

	/** One line per target: the candidates that light it. Read by an exact solver outside the game. */
	private static void dump(String label, int planned, java.util.Collection<BitSet> rows, int candidates) {
		StringBuilder out = new StringBuilder(planned + " " + candidates + "\n");
		for (BitSet row : rows) {
			row.stream().forEach(c -> out.append(c).append(' '));
			out.append('\n');
		}
		try {
			Path folder = FabricLoader.getInstance().getGameDir().resolve("lighttest-instances");
			Files.createDirectories(folder);
			Files.writeString(folder.resolve(label.replace(' ', '_') + ".txt"), out, StandardCharsets.UTF_8);
		} catch (IOException e) {
			write("  could not write the instance: " + e);
		}
	}

	/** Places a source on every planned spot at once, then no surface that could be lit may stay red. */
	private static void placeAll(String label, String block) {
		int[] expected = new int[2];
		add(label + ": place a source on every planned spot", () -> {
			Minecraft mc = Minecraft.getInstance();
			expected[0] = feature.planner().state().uncoverable();
			expected[1] = spots().size();
			for (LightPlan.Spot spot : spots()) {
				set(mc, spot.pos(), block);
			}
		}, () -> feature.idle() && (expected[1] == 0 || feature.planner().rounds() > roundsBefore), () -> {
			Set<Long> left = red(Minecraft.getInstance());
			write("  after placing " + expected[1] + ": red " + left.size() + ", plan " + spots().size());
			return expect(left.size() <= expected[0] && spots().isEmpty(), "still red " + left.size() + " (allowed " + expected[0] + "), plan "
				+ spots().size());
		});
	}

	private static void shot(String name) {
		add("screenshot " + name, () -> Minecraft.getInstance().gui.hud.getChat().clearMessages(false), null, () -> {
			Minecraft mc = Minecraft.getInstance();
			Screenshot.grab(mc.gameDirectory, name + ".png", mc.gameRenderer.mainRenderTarget(), 1, c -> write("  saved " + name));
			return null;
		});
	}

	/** Hover high above the platform looking straight down (the screenshots). */
	private static void topView(Minecraft mc, boolean up) {
		cmd(mc, "tp @s " + (cx + 0.5) + " " + (up ? FLOOR + 15.2 : FLOOR + 1) + " " + (cz + 0.5) + " 180 " + (up ? 90 : 35));
		mc.player.getAbilities().flying = up;
		mc.player.onUpdateAbilities();
	}

	private static String spotList(List<LightPlan.Spot> list) {
		StringBuilder out = new StringBuilder();
		for (LightPlan.Spot s : list) {
			out.append('(').append(s.pos().getX() - cx).append(',').append(s.pos().getZ() - cz).append(':').append(s.covers().length).append(") ");
		}
		return out.toString();
	}

	private static Set<Long> cells(List<LightPlan.Spot> list) {
		Set<Long> out = new HashSet<>();
		for (LightPlan.Spot s : list) {
			out.add(s.cell());
		}
		return out;
	}

	private static void build(Minecraft mc) {
		BlockPos feet = mc.player.blockPosition();
		// The middle of the chunk: with distance 1 the area is the 48 x 48 blocks around it.
		cx = (feet.getX() >> 4 << 4) + 8;
		cz = (feet.getZ() >> 4 << 4) + 8;
		int[] saved = { KhoaConfig.getInt(ID, "level", 0), KhoaConfig.getInt(ID, "distance", 2), KhoaConfig.getInt(ID, "plan_source", 0),
			KhoaConfig.getInt(ID, "min_y", 60), KhoaConfig.getInt(ID, "max_y", 320) };
		boolean savedPlan = LightLevelOverlayFeature.planning();
		oldFov = mc.options.fov().get();

		add("setup: platform in the sky, creative", () -> {
			cmd(mc, "gamemode creative");
			cmd(mc, "time set midnight");
			cmd(mc, "tp @s " + (cx + 0.5) + " " + (FLOOR + 8) + " " + (cz + 0.5) + " 180 35");
			mc.player.getAbilities().flying = true;
			mc.player.onUpdateAbilities();
			wipe(mc);
			mc.options.fov().set(105);
			settings(0, 0);
			KhoaConfig.put(ID, "distance", 1);
			KhoaConfig.put(ID, "min_y", MIN_Y);
			KhoaConfig.put(ID, "max_y", MAX_Y);
			KhoaConfig.put(ID, "plan", false);
		}, () -> !mc.level.getBlockState(new BlockPos(cx, FLOOR, cz)).isAir(), () -> {
			topView(mc, false);
			return expect(!mc.level.getBlockState(new BlockPos(cx, FLOOR, cz)).isAir(), "no platform (cheats off?)");
		});

		add("overlay on, placement on through the command", () -> {
			if (!feature.isActive()) {
				feature.activate(mc);
			}
			cmd(mc, "khoatoolsclient lightlevel plan");
		}, () -> feature.idle(), () -> expect(LightLevelOverlayFeature.planning() && feature.isActive(), "command did nothing"));

		// ---- A: an empty, completely dark area
		planned("A: open dark area, level 0", () -> {
			topView(mc, true);
			feature.planner().restart();
		}, () -> {
			write("  " + spotList(spots()));
			return checkPlan(mc, "min open level 0");
		});
		shot("lighttest-1-open");

		// ---- B: placing exactly as planned, one by one, must not move any other spot
		for (int i = 0; i < 3; i++) {
			add("B: place planned spot " + (i + 1) + ", the others stay", () -> {
				remembered = new ArrayList<>(spots());
				set(mc, remembered.get(0).pos(), "torch");
			}, () -> feature.idle() && feature.planner().rounds() > roundsBefore, () -> {
				Set<Long> wanted = cells(remembered.subList(1, remembered.size()));
				Set<Long> now = cells(spots());
				write("  before " + remembered.size() + ", now " + now.size());
				for (long cell : remembered.get(0).covers()) {
					if (red(mc).contains(cell)) {
						return "a predicted cell stayed red: " + BlockPos.of(cell);
					}
				}
				return expect(now.equals(wanted), "plan changed: " + spotList(spots()));
			});
		}
		shot("lighttest-2-three-placed");
		placeAll("B", "torch");
		shot("lighttest-3-done");

		// ---- C: one dark patch inside a lit area -> one source in its middle
		int px = 5;
		int pz = -4;
		planned("C: lit area with one dark diamond around (5,-4), level 7", () -> {
			wipe(mc);
			settings(7, 0);
			// Torches 10 steps from the patch centre leave dark exactly the cells within 3 steps of it.
			for (int x = -24; x <= 24; x++) {
				for (int z = -24; z <= 24; z++) {
					int away = Math.abs(x - px) + Math.abs(z - pz);
					boolean ring = away == 10;
					boolean outer = away > 12 && Math.floorMod(x + 3 * z, 11) == 0;
					boolean outer2 = away > 12 && Math.floorMod(x, 4) == 0 && Math.floorMod(z, 4) == 0;
					if (ring || outer || outer2) {
						set(mc, cx + x, FLOOR + 1, cz + z, "torch");
					}
				}
			}
		}, () -> {
			write("  " + spotList(spots()));
			String bad = checkPlan(mc, "patch");
			if (bad != null) {
				return bad;
			}
			return expect(spots().size() == 1 && spots().get(0).pos().equals(new BlockPos(cx + px, FLOOR + 1, cz + pz)),
				"wanted one spot in the middle, got " + spotList(spots()));
		});
		shot("lighttest-4-middle");
		placeAll("C", "torch");

		// ---- D: random torches and obstacles
		String[] obstacles = { "stone", "stone", "smooth_stone_slab", "glass", "oak_stairs", "oak_leaves", "water" };
		int[][] cases = { { 1, 0, 0 }, { 2, 0, 0 }, { 3, 4, 0 }, { 4, 7, 0 }, { 5, 7, 0 }, { 6, 11, 0 }, { 7, 0, 4 }, { 8, 3, 2 }, { 9, 7, 3 } };
		String[] blocks = { "torch", "lantern", "soul_torch", "redstone_torch", "glowstone" };
		for (int[] c : cases) {
			int seed = c[0];
			String label = "D" + seed + " level " + c[1] + " " + blocks[c[2]];
			planned(label + ": random torches, walls, pillars", () -> {
				wipe(mc);
				settings(c[1], c[2]);
				Random random = new Random(seed * 977L);
				for (int w = 0; w < 4 + random.nextInt(6); w++) {
					int x = random.nextInt(41) - 20;
					int z = random.nextInt(41) - 20;
					int length = 2 + random.nextInt(14);
					int height = 1 + random.nextInt(3);
					String block = obstacles[random.nextInt(obstacles.length - 1)];
					boolean alongX = random.nextBoolean();
					cmd(mc, "fill " + (cx + x) + " " + (FLOOR + 1) + " " + (cz + z) + " " + (cx + x + (alongX ? length : 0)) + " " + (FLOOR + height) + " "
						+ (cz + z + (alongX ? 0 : length)) + " " + block);
				}
				int torches = random.nextInt(22);
				for (int t = 0; t < torches; t++) {
					cmd(mc, "setblock " + (cx + random.nextInt(47) - 23) + " " + (FLOOR + 1) + " " + (cz + random.nextInt(47) - 23) + " torch keep");
				}
			}, () -> checkPlan(mc, label));
			planned(label + ": Recalculate", () -> feature.planner().restart(), () -> checkPlan(mc, "min " + label));
			if (seed == 4 || seed == 9) {
				shot("lighttest-5-random-" + seed);
			}
			placeAll(label, blocks[c[2]]);
		}

		// ---- E: a source in the wrong place only reshuffles its neighbourhood
		planned("E: open dark area, level 9 (many small sources)", () -> {
			wipe(mc);
			settings(9, 0);
		}, () -> checkPlan(mc, "min open level 9"));
		shot("lighttest-6-before-wrong");
		BlockPos[] wrong = new BlockPos[1];
		planned("E: torch next to a planned spot instead of on it", () -> {
			remembered = new ArrayList<>(spots());
			Set<Long> taken = cells(remembered);
			// the planned spot nearest to a point off the centre, then 2 blocks beside it
			LightPlan.Spot target = remembered.stream()
				.min(Comparator.comparingDouble(s -> s.pos().distSqr(new BlockPos(cx - 7, FLOOR + 1, cz - 7)))).orElseThrow();
			for (BlockPos p : new BlockPos[] { target.pos().offset(2, 0, 1), target.pos().offset(-2, 0, 1), target.pos().offset(1, 0, 2),
				target.pos().offset(3, 0, 0) }) {
				if (!taken.contains(p.asLong())) {
					wrong[0] = p;
					break;
				}
			}
			set(mc, wrong[0], "torch");
		}, () -> {
			Set<Long> now = cells(spots());
			int farLost = 0;
			int far = 0;
			int kept = 0;
			for (LightPlan.Spot s : remembered) {
				kept += now.contains(s.cell()) ? 1 : 0;
				// reach 4 each: two sources further apart than this cannot share a cell
				if (s.pos().distManhattan(wrong[0]) > 9) {
					far++;
					farLost += now.contains(s.cell()) ? 0 : 1;
				}
			}
			write("  wrong torch at " + (wrong[0].getX() - cx) + "," + (wrong[0].getZ() - cz) + ": " + remembered.size() + " -> " + now.size()
				+ " spots, " + kept + " unchanged, far ones " + far + " (lost " + farLost + ")");
			String bad = checkPlan(mc, "after wrong torch");
			return bad != null ? bad : expect(farLost == 0 && far > 0 && kept < remembered.size(), "unrelated spots changed: " + farLost);
		});
		shot("lighttest-7-after-wrong");
		planned("E: a wall across part of the plan", () -> {
			remembered = new ArrayList<>(spots());
			cmd(mc, "fill " + (cx + 2) + " " + (FLOOR + 1) + " " + (cz + 8) + " " + (cx + 12) + " " + (FLOOR + 3) + " " + (cz + 8) + " stone");
		}, () -> {
			Set<Long> now = cells(spots());
			int farLost = 0;
			for (LightPlan.Spot s : remembered) {
				if (s.pos().getZ() < cz - 2 && !now.contains(s.cell())) {
					farLost++;
				}
			}
			String bad = checkPlan(mc, "after wall");
			return bad != null ? bad : expect(farLost == 0, "spots on the far side changed: " + farLost);
		});
		placeAll("E", "torch");

		// ---- F: walking keeps the spots behind the frontier
		planned("F: open dark area again", () -> {
			wipe(mc);
			settings(9, 0);
		}, () -> checkPlan(mc, "before walking"));
		planned("F: move one chunk", () -> {
			remembered = new ArrayList<>(spots());
			cmd(mc, "tp @s " + (cx + 16.5) + " " + (FLOOR + 15.2) + " " + (cz + 0.5) + " 180 90");
		}, () -> {
			Set<Long> now = cells(spots());
			int inner = 0;
			int lost = 0;
			for (LightPlan.Spot s : remembered) {
				int x = s.pos().getX() - cx;
				if (x > -3 && x < 18 && Math.abs(s.pos().getZ() - cz) < 18) {
					inner++;
					lost += now.contains(s.cell()) ? 0 : 1;
				}
			}
			write("  spots well inside both areas: " + inner + ", changed " + lost);
			String bad = checkPlan(mc, "after walking");
			return bad != null ? bad : expect(inner > 0 && lost == 0, "inner spots moved: " + lost);
		});

		// ---- G: the height range
		planned("G: Max Y below the platform hides it", () -> {
			topView(mc, true);
			KhoaConfig.put(ID, "max_y", FLOOR);
		}, () -> expect(spots().isEmpty() && feature.shownCells() == 0, "spots " + spots().size() + ", red " + feature.shownCells()));
		planned("G: Min Y = Max Y = the platform surface", () -> {
			KhoaConfig.put(ID, "min_y", FLOOR + 1);
			KhoaConfig.put(ID, "max_y", FLOOR + 1);
		}, () -> expect(feature.shownCells() == 48 * 48 && !spots().isEmpty(), "red " + feature.shownCells()));

		// ---- H: the default range over the natural terrain of the world
		for (int distance : new int[] { 2, 5 }) {
			long[] since = new long[1];
			planned("H: Y 60..320, distance " + distance + " chunks", () -> {
				since[0] = System.currentTimeMillis();
				settings(0, 0);
				KhoaConfig.put(ID, "min_y", 60);
				KhoaConfig.put(ID, "max_y", 320);
				KhoaConfig.put(ID, "distance", distance);
			}, () -> {
				write("  plan shown after " + (System.currentTimeMillis() - since[0]) + " ms, red drawn " + feature.shownCells());
				return checkPlan(mc, "terrain " + distance);
			});
			shot("lighttest-7b-terrain-" + distance);
		}
		planned("H: level 14 with a torch cannot be reached", () -> {
			KhoaConfig.put(ID, "distance", 1);
			settings(14, 0);
		}, () -> expect(spots().isEmpty() && feature.planner().state().uncoverable() > 0, "spots " + spots().size()));

		add("config screen shows the new rows", () -> {
			settings(0, 0);
			openConfig(mc);
		}, null, () -> expect(mc.gui.screen() instanceof KhoaToolsScreen, "screen " + mc.gui.screen()));
		shot("lighttest-8-config");
		add("scroll to the new rows", () -> {
			for (int i = 0; i < 2; i++) {
				mc.gui.screen().mouseScrolled(mc.gui.screen().width / 2.0, mc.gui.screen().height - 30, 0, -3);
			}
		}, null, () -> null);
		shot("lighttest-9-config-rows");

		add("restore", () -> {
			mc.gui.setScreen(null);
			wipe(mc);
			cmd(mc, "fill " + (cx - HALF) + " " + FLOOR + " " + (cz - HALF) + " " + (cx + HALF) + " " + FLOOR + " " + (cz + HALF) + " air");
			cmd(mc, "tp @s " + (feet.getX() + 0.5) + " " + feet.getY() + " " + (feet.getZ() + 0.5));
			mc.options.fov().set(oldFov);
			settings(saved[0], saved[2]);
			KhoaConfig.put(ID, "distance", saved[1]);
			KhoaConfig.put(ID, "min_y", saved[3]);
			KhoaConfig.put(ID, "max_y", saved[4]);
			KhoaConfig.put(ID, "plan", savedPlan);
			if (feature.isActive()) {
				feature.activate(mc);
			}
		}, null, null);
	}

	/** Opens the menu straight on this feature's config page (dev test only, so reflection is fine). */
	private static void openConfig(Minecraft mc) {
		try {
			KhoaToolsScreen screen = new KhoaToolsScreen();
			mc.gui.setScreen(screen);
			Method cards = KhoaToolsScreen.class.getDeclaredMethod("cards");
			cards.setAccessible(true);
			Field configCard = KhoaToolsScreen.class.getDeclaredField("configCard");
			configCard.setAccessible(true);
			for (Object card : (List<?>) cards.invoke(screen)) {
				Method id = card.getClass().getDeclaredMethod("id");
				id.setAccessible(true);
				if (ID.equals(id.invoke(card))) {
					configCard.set(screen, card);
				}
			}
			Method rebuild = net.minecraft.client.gui.screens.Screen.class.getDeclaredMethod("rebuildWidgets");
			rebuild.setAccessible(true);
			rebuild.invoke(screen);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
