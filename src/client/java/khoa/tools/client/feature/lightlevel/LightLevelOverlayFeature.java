package khoa.tools.client.feature.lightlevel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;

import khoa.tools.KhoaTools;
import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.setting.Setting;

/**
 * Light Level Overlay: paints red every surface (top of a solid block with free space above it) whose BLOCK light
 * level is at or below the slider, between Min Y and Max Y and within the chunk distance around you. Sky light is
 * ignored. Rescanned (off the client thread) on every block update, light update and when you enter another chunk.
 */
public final class LightLevelOverlayFeature implements KhoaFeature {
	private static final String ID = "lightlevel";
	private static final int MAX_DISTANCE = 8;
	private static final int DEFAULT_DISTANCE = 2;
	private static final int DEFAULT_MIN_Y = 60;
	private static final int DEFAULT_MAX_Y = 320;
	/** Most surfaces drawn at once (each one is a quad); the nearest ones win. */
	private static final int MAX_CELLS = 30000;
	/** Lifts the red overlay off the block so it does not flicker against it. */
	private static final double LIFT = 0.003;
	/** After a block / light update, scan once more this many ticks later so the light engine can catch up. */
	private static final int SETTLE_TICKS = 4;
	/** Never start scans closer together than this. */
	private static final int MIN_INTERVAL = 4;
	/** The area follows you to another chunk only once you are this many blocks into it (no flicker on the border). */
	private static final int CHUNK_MARGIN = 3;

	private static final GizmoStyle STYLE = GizmoStyle.fill(ARGB.color(110, 255, 40, 40));

	/** Light sources "Optimal placement" can plan for. */
	private static final List<String> SOURCES = List.of("Torch (14)", "Lantern (15)", "Soul torch / lantern (10)", "Redstone torch (7)",
		"Full block: glowstone etc. (15)");
	private static final int[] EMISSION = { 14, 15, 10, 7, 15 };
	private static final int FULL_BLOCK = 4;

	private static volatile boolean dirty;

	private record Cell(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double y) {
	}

	private record Scan(LightScan.Snapshot snapshot, List<Cell> cells, long millis) {
	}

	private final LightPlanController planner = new LightPlanController();
	private final ExecutorService scanner = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "KhoaTools light scan");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});
	private boolean on;
	private List<Cell> cells = List.of();
	private Future<Scan> job;
	private boolean needsScan;
	private int settle;
	private int sinceScan;
	private ClientLevel level;
	private LightScan.Area area;
	private LightScan.Settings scanned;
	private int chunkX;
	private int chunkZ;
	private boolean wasPlanning;
	private long copyMillis;
	private long scanMillis;

	/** Called from the mixins: something changed that can change light or surfaces. */
	public static void markDirty() {
		dirty = true;
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Light Level Overlay";
	}

	@Override
	public String description() {
		return "Paints red every surface whose block light level is at or below the slider (sky light is ignored). Only between Min Y and Max Y, within Distance chunks around you. Optimal placement marks (cyan, nearest one yellow) where to put as few light sources as possible so that nothing stays red; the number is how many red surfaces that source fixes.";
	}

	@Override
	public boolean isActive() {
		return on;
	}

	@Override
	public List<Setting> settings() {
		return List.of(
			Setting.slider("level", "Red light level", "Surfaces with block light level at or below this get the red overlay (0 - 15).", 0, 15,
				LightLevelOverlayFeature::threshold, v -> KhoaConfig.put(ID, "level", v)),
			Setting.pair("y_range", "Min Y / Max Y", "Only surfaces whose free block is between these two heights are shown and planned.",
				"Min Y", LightLevelOverlayFeature::minY, v -> KhoaConfig.put(ID, "min_y", (int) v),
				"Max Y", LightLevelOverlayFeature::maxY, v -> KhoaConfig.put(ID, "max_y", (int) v)),
			Setting.slider("distance", "Distance (chunks)", "Chunks around your chunk that are shown and planned (2 = 5 x 5 chunks). Bigger = slower.", 1, MAX_DISTANCE,
				LightLevelOverlayFeature::distance, v -> KhoaConfig.put(ID, "distance", v)),
			Setting.bool("plan", "Optimal placement", "Mark where to place light sources so that every surface gets brighter than the red light level, using as few as possible.",
				LightLevelOverlayFeature::planning, v -> KhoaConfig.put(ID, "plan", v)),
			Setting.dropdown("plan_source", "Light source", "The light source Optimal placement plans for.", SOURCES,
				LightLevelOverlayFeature::source, v -> KhoaConfig.put(ID, "plan_source", v)),
			Setting.action("plan_again", "Recalculate", "Throw the current plan away and make a new one for the whole area.", "Recalculate", planner::restart));
	}

	static boolean planning() {
		return KhoaConfig.getBool(ID, "plan", false);
	}

	private static int source() {
		return Math.max(0, Math.min(SOURCES.size() - 1, KhoaConfig.getInt(ID, "plan_source", 0)));
	}

	private static int minY() {
		return KhoaConfig.getInt(ID, "min_y", DEFAULT_MIN_Y);
	}

	private static int maxY() {
		return KhoaConfig.getInt(ID, "max_y", DEFAULT_MAX_Y);
	}

	private static int distance() {
		return Math.max(1, Math.min(MAX_DISTANCE, KhoaConfig.getInt(ID, "distance", DEFAULT_DISTANCE)));
	}

	/** Nothing is waiting to be scanned or planned. */
	boolean idle() {
		return !needsScan && settle == 0 && job == null && (!planning() || planner.idle());
	}

	/** Client thread time of the last chunk copy, and worker times of the last scan and plan. */
	String timings() {
		return "copy " + copyMillis + " ms on the game thread, scan " + scanMillis + " ms, plan " + planner.planMillis() + " ms";
	}

	int shownCells() {
		return cells.size();
	}

	LightPlanController planner() {
		return planner;
	}

	@Override
	public void registerCommands(LiteralArgumentBuilder<FabricClientCommandSource> root, CommandBuildContext context) {
		root.then(ClientCommands.literal(ID)
			.executes(ctx -> {
				activate(ctx.getSource().getClient());
				return 1;
			})
			.then(ClientCommands.literal("plan").executes(ctx -> {
				boolean next = !planning();
				KhoaConfig.put(ID, "plan", next);
				Minecraft mc = ctx.getSource().getClient();
				if (next && !on) {
					activate(mc);
				}
				KhoaFeature.say(mc, "Optimal placement: " + (next ? "on" : "off"));
				return 1;
			})));
	}

	private static int threshold() {
		return Math.max(0, Math.min(15, KhoaConfig.getInt(ID, "level", 0)));
	}

	@Override
	public void activate(Minecraft mc) {
		on = !on;
		reset();
		KhoaFeature.say(mc, "Light Level Overlay: " + (on ? "on (block light <= " + threshold() + ")" : "off"));
	}

	private void reset() {
		cells = List.of();
		if (job != null) {
			job.cancel(true);
			job = null;
		}
		area = null;
		scanned = null;
		needsScan = false;
		settle = 0;
		sinceScan = MIN_INTERVAL;
		planner.clear();
	}

	@Override
	public void onInit() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			tick(mc);
			LightPlanSelfTest.tick(mc, this);
		});
		LevelRenderEvents.END_EXTRACTION.register(context -> draw());
	}

	private void tick(Minecraft mc) {
		if (!on) {
			dirty = false;
			return;
		}
		if (level != mc.level) {
			level = mc.level;
			reset();
		}
		if (mc.level == null || mc.player == null) {
			return;
		}

		BlockPos here = mc.player.blockPosition();
		if (area == null || left(here.getX(), chunkX)) {
			chunkX = here.getX() >> 4;
		}
		if (area == null || left(here.getZ(), chunkZ)) {
			chunkZ = here.getZ() >> 4;
		}
		int distance = distance();
		int bottom = mc.level.getMinY();
		int top = bottom + mc.level.getHeight() - 1;
		int low = Math.max(bottom, Math.min(top, Math.min(minY(), maxY())));
		int high = Math.max(bottom, Math.min(top, Math.max(minY(), maxY())));
		LightScan.Area wantedArea = new LightScan.Area((chunkX - distance) << 4, ((chunkX + distance) << 4) + 15, (chunkZ - distance) << 4,
			((chunkZ + distance) << 4) + 15, low, high);
		int source = source();
		LightScan.Settings wanted = new LightScan.Settings(EMISSION[source], source == FULL_BLOCK, threshold());
		boolean planning = planning();
		if (!wantedArea.equals(area) || !wanted.equals(scanned) || planning != wasPlanning) {
			area = wantedArea;
			scanned = wanted;
			wasPlanning = planning;
			needsScan = true;
			if (!planning) {
				planner.clear();
			}
		}
		if (dirty) {
			dirty = false;
			needsScan = true;
			settle = SETTLE_TICKS;
		} else if (settle > 0 && --settle == 0) {
			needsScan = true;
		}

		if (job != null && job.isDone()) {
			try {
				Scan scan = job.get();
				cells = scan.cells();
				scanMillis = scan.millis();
				if (planning && scan.snapshot().area().equals(area) && scan.snapshot().settings().equals(scanned)) {
					planner.offer(scan.snapshot());
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (ExecutionException e) {
				KhoaTools.LOGGER.error("Light overlay scan failed ({})", area, e.getCause());
			}
			job = null;
		}

		if (sinceScan < MIN_INTERVAL) {
			sinceScan++;
		}
		if (needsScan && job == null && sinceScan >= MIN_INTERVAL) {
			long start = System.nanoTime();
			LightScan.Copy copy = LightScan.copy(mc.level, area);
			copyMillis = (System.nanoTime() - start) / 1_000_000;
			LightScan.Settings settings = scanned;
			Vec3 from = mc.player.position();
			job = scanner.submit(() -> {
				long begin = System.nanoTime();
				LightScan.Snapshot snapshot = LightScan.scan(copy, settings);
				List<Cell> out = cells(snapshot.dark(), from);
				return new Scan(snapshot, out, (System.nanoTime() - begin) / 1_000_000);
			});
			needsScan = false;
			sinceScan = 0;
		}

		if (planning) {
			planner.tick(mc);
		}
	}

	private static boolean left(int position, int chunk) {
		return position < (chunk << 4) - CHUNK_MARGIN || position > (chunk << 4) + 15 + CHUNK_MARGIN;
	}

	/** The quads of the red surfaces, only the ones nearest to the player when there are too many. */
	private static List<Cell> cells(long[] dark, Vec3 from) {
		long[] shown = dark;
		if (dark.length > MAX_CELLS) {
			double[] distance = new double[dark.length];
			for (int i = 0; i < dark.length; i++) {
				double dx = BlockPos.getX(dark[i]) + 0.5 - from.x;
				double dy = BlockPos.getY(dark[i]) - from.y;
				double dz = BlockPos.getZ(dark[i]) + 0.5 - from.z;
				distance[i] = dx * dx + dy * dy + dz * dz;
			}
			double[] sorted = distance.clone();
			Arrays.sort(sorted);
			double limit = sorted[MAX_CELLS - 1];
			shown = new long[MAX_CELLS];
			int n = 0;
			for (int i = 0; i < dark.length && n < MAX_CELLS; i++) {
				if (distance[i] <= limit) {
					shown[n++] = dark[i];
				}
			}
		}
		List<Cell> out = new ArrayList<>(shown.length);
		for (long cell : shown) {
			int x = BlockPos.getX(cell);
			int z = BlockPos.getZ(cell);
			double top = BlockPos.getY(cell) + LIFT;
			// Counter-clockwise as seen from above (draw() flips it when you look from below).
			out.add(new Cell(new Vec3(x, top, z), new Vec3(x, top, z + 1), new Vec3(x + 1, top, z + 1), new Vec3(x + 1, top, z), top));
		}
		return out;
	}

	private void draw() {
		Minecraft mc = Minecraft.getInstance();
		if (!on || mc.player == null) {
			return;
		}
		if (planning()) {
			planner.draw(mc);
		}
		double eyeY = mc.player.getEyePosition().y;
		for (Cell c : cells) {
			if (eyeY > c.y()) {
				Gizmos.rect(c.p0(), c.p1(), c.p2(), c.p3(), STYLE);
			} else {
				Gizmos.rect(c.p0(), c.p3(), c.p2(), c.p1(), STYLE);
			}
		}
	}
}
