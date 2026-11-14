package khoa.tools.client.feature.illegal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import khoa.api.path.KhoaPathfinding;
import khoa.api.path.PathOptions;
import khoa.tools.client.feature.illegal.spawnerfinder.SpawnerDigPath;

/**
 * Stare at a spawner (through walls, from any distance) for a few seconds and the safe path to it is drawn.
 * Stare at another one for the same time and the path switches to it.
 *
 * The path comes from {@link KhoaPathfinding}: only positions a player can stand in (at least 1 wide, 2 high), no lava / fire /
 * magma / cactus, drops of at most 3 blocks, loaded chunks only. When no walkable path exists, {@link SpawnerDigPath} finds the
 * route that needs the fewest blocks mined (drawn in orange, blocks to break in red).
 */
public final class StarePath {
	private static final int LOST_GRACE_TICKS = 10;   // aim may drift off for half a second without resetting the timer
	private static final int REPATH_COOLDOWN = 60;    // ticks between automatic recalculations
	private static final double OFF_PATH_BLOCKS = 4.0;
	private static final int PATH_COLOR = 0xFF33FF66;
	private static final int DIG_PATH_COLOR = 0xFFFF9933;
	private static final int MINE_COLOR = 0xFFFF3333;
	private static final int MAX_DRAWN_MINE = 300;
	private static final float PATH_WIDTH = 3.0f;
	private static final int MAX_DRAWN_POINTS = 600;
	private static final int GOAL_RADIUS = 16;        // look for a standing spot this close to the spawner
	private static final int MAX_GOAL_TRIES = 8;      // pathfinder calls per attempt (each can cost up to ~150 ms)
	private static final int SKIP_POCKET_SQ = 16;     // after a goal fails, skip goals within 4 blocks of it

	private BlockPos candidate;
	private int stareTicks;
	private int lostTicks;
	private int shownSeconds = -1;

	private BlockPos target;                   // the spawner the current path leads to
	private List<BlockPos> path = List.of();
	private List<BlockPos> mine = List.of();    // blocks to break along the route when no walkable path exists
	private int lastRemaining;
	private int sinceRepath;

	private final String name;
	private final BooleanSupplier enabled;
	private final IntSupplier stareSeconds;
	private final Supplier<List<BlockPos>> targets;
	private final Function<BlockPos, String> label;
	/** true = spawner profile (big search, dig route fallback); false = light profile (small search, straight line fallback). */
	private final boolean heavy;
	private final int goalRadius;
	private final int maxGoalTries;
	private BlockPos directTo; // light profile: no walkable path, a straight line is shown instead

	/**
	 * @param enabled      path feature on (feature card on AND its Pathfinding setting on)
	 * @param stareSeconds how long to look at a target before the path is calculated
	 * @param targets      blocks that can be stared at (checked through walls)
	 * @param label        name of a target for chat messages
	 * @param heavy        spawner profile (up to 8 goals, 250k nodes, dig route when no walkable path) or light profile (4 goals within 6 blocks, 60k nodes, straight line when no path)
	 */
	public StarePath(String name, BooleanSupplier enabled, IntSupplier stareSeconds, Supplier<List<BlockPos>> targets,
		Function<BlockPos, String> label, boolean heavy) {
		this.name = name;
		this.enabled = enabled;
		this.stareSeconds = stareSeconds;
		this.targets = targets;
		this.label = label;
		this.heavy = heavy;
		this.goalRadius = heavy ? GOAL_RADIUS : 6;
		this.maxGoalTries = heavy ? MAX_GOAL_TRIES : 4;
	}

	public void clear() {
		generation++; // drops a search that is still running
		pending = null;
		candidate = null;
		stareTicks = 0;
		lostTicks = 0;
		shownSeconds = -1;
		target = null;
		path = List.of();
		mine = List.of();
		directTo = null;
	}

	// ---- tick ----

	public void onClientTick(Minecraft mc) {
		if (!enabled.getAsBoolean() || mc.player == null || mc.level == null) {
			clear();
			return;
		}
		pollPending(mc);
		if (mc.gui.screen() != null) {
			return; // menus open: pause the timer, keep the drawn path
		}

		BlockPos looked = lookedAtSpawner(mc.player);
		if (looked == null) {
			if (candidate != null && ++lostTicks > LOST_GRACE_TICKS) {
				candidate = null;
				stareTicks = 0;
				shownSeconds = -1;
			}
		} else {
			lostTicks = 0;
			if (!looked.equals(candidate)) {
				candidate = looked;
				stareTicks = 0;
				shownSeconds = -1;
			} else {
				stareTicks++;
			}
			if (!looked.equals(target)) {
				int needed = Math.max(1, stareSeconds.getAsInt() * 20);
				if (stareTicks >= needed) {
					computePath(mc, looked);
				} else {
					showCountdown(mc, (needed - stareTicks + 19) / 20);
				}
			}
		}

		followAndRepath(mc);
	}

	private void showCountdown(Minecraft mc, int secondsLeft) {
		if (secondsLeft != shownSeconds) {
			shownSeconds = secondsLeft;
			say(mc, "§e" + name + " path in " + secondsLeft + "s... keep looking");
		}
	}

	// ---- what is the player looking at ----

	/**
	 * The highlighted spawner closest to the crosshair line. Walls and distance do not matter: this is a plain line-to-block test, so
	 * a far spawner behind rock counts too. The allowed miss grows a little with distance because far blocks look tiny.
	 */
	private BlockPos lookedAtSpawner(Player player) {
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		BlockPos best = null;
		double bestMiss = Double.MAX_VALUE;
		for (BlockPos pos : targets.get()) {
			Vec3 toCenter = Vec3.atCenterOf(pos).subtract(eye);
			double along = toCenter.dot(look);
			if (along < 1.0) {
				continue; // behind you
			}
			double missSq = toCenter.lengthSqr() - along * along;
			double allowed = 0.87 + along * 0.01; // half a block diagonal + about 0.6 degrees
			if (missSq <= allowed * allowed && missSq < bestMiss) {
				bestMiss = missSq;
				best = pos;
			}
		}
		return best;
	}

	// ---- computing the path ----

	/** Every spot within GOAL_RADIUS of the spawner where a player can stand, closest to the spawner first. */
	private List<BlockPos> goalsAround(Level level, BlockPos spawner) {
		List<BlockPos> goals = new ArrayList<>();
		int r = goalRadius;
		long rSq = (long) r * r;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					if ((long) dx * dx + (long) dy * dy + (long) dz * dz > rSq || (dx == 0 && dy == 0 && dz == 0)) {
						continue;
					}
					m.set(spawner.getX() + dx, spawner.getY() + dy, spawner.getZ() + dz);
					if (canStand(level, m)) {
						goals.add(m.immutable());
					}
				}
			}
		}
		goals.sort(Comparator.comparingDouble(g -> g.distSqr(spawner)));
		return goals;
	}

	private static boolean canStand(Level level, BlockPos.MutableBlockPos feet) {
		if (!level.hasChunkAt(feet) || level.isOutsideBuildHeight(feet.getY() - 1) || level.isOutsideBuildHeight(feet.getY() + 1)) {
			return false;
		}
		BlockState at = level.getBlockState(feet);
		if (!at.getCollisionShape(level, feet).isEmpty() || !at.getFluidState().isEmpty() || at.is(Blocks.FIRE) || at.is(Blocks.SOUL_FIRE)) {
			return false;
		}
		feet.move(Direction.UP);
		BlockState head = level.getBlockState(feet);
		boolean headOk = head.getCollisionShape(level, feet).isEmpty() && head.getFluidState().isEmpty();
		feet.move(Direction.DOWN, 2);
		BlockState floor = level.getBlockState(feet);
		boolean floorOk = floor.isFaceSturdy(level, feet, Direction.UP) && !floor.is(Blocks.MAGMA_BLOCK) && !floor.is(Blocks.CACTUS)
			&& floor.getFluidState().isEmpty();
		feet.move(Direction.UP);
		return headOk && floorOk;
	}

	private PathOptions options() {
		// far spawners need a bigger search than the default 30000 positions / 50 ms; the result is cached, so one hitch per target is fine
		if (!heavy) {
			return PathOptions.defaults().maxNodes(60_000).maxMillis(40).heuristicWeight(1.8).maxFall(3);
		}
		return PathOptions.defaults().maxNodes(250_000).maxMillis(150).heuristicWeight(1.5).maxFall(3);
	}

	/** The search runs on its own thread: a failing search (8 goals + the dig search) used to freeze the game for seconds. */
	private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "khoatools-stare-path");
		thread.setDaemon(true);
		return thread;
	});

	/** Result of one search, applied on the main thread. {@code message} is built there too. */
	private record Computed(List<BlockPos> path, List<BlockPos> mine, BlockPos directTo, boolean failed, Supplier<String> message) {
	}

	private int generation;
	private java.util.concurrent.CompletableFuture<Computed> pending;
	private int pendingGeneration;

	private void computePath(Minecraft mc, BlockPos spawner) {
		target = spawner;
		sinceRepath = 0;
		shownSeconds = -1;
		directTo = null;

		final Level level = mc.level;
		final BlockPos start = mc.player.blockPosition();
		final int searchId = ++generation;
		pendingGeneration = searchId;
		say(mc, "§7" + name + ": calculating path...");
		pending = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
			try {
				return compute(level, start, spawner);
			} catch (RuntimeException e) {
				khoa.tools.KhoaTools.LOGGER.warn("Path search failed", e);
				return new Computed(List.of(), List.of(), null, true, () -> "§cPath search failed (see log)");
			}
		}, WORKER);
	}

	/** Applies a finished search, if it still matches the current target. */
	private void pollPending(Minecraft mc) {
		java.util.concurrent.CompletableFuture<Computed> future = pending;
		if (future == null || !future.isDone()) {
			return;
		}
		pending = null;
		Computed c = future.getNow(null);
		if (c == null || pendingGeneration != generation || target == null) {
			return; // a newer search or clear() made this one stale
		}
		if (c.failed()) {
			fail();
			say(mc, c.message().get());
			return;
		}
		path = c.path();
		this.mine = c.mine();
		directTo = c.directTo();
		lastRemaining = this.mine.size();
		say(mc, c.message().get());
	}

	/** The search itself: no field writes, no chat, safe for the worker thread. */
	private Computed compute(Level level, BlockPos start, BlockPos spawner) {
		KhoaPathfinding.Status lastFailure = KhoaPathfinding.Status.END_BLOCKED;
		List<BlockPos> failed = new ArrayList<>();
		int tried = 0;
		PathOptions options = options();
		for (BlockPos goal : goalsAround(level, spawner)) {
			if (isInFailedPocket(goal, failed)) {
				continue;
			}
			KhoaPathfinding.PathResult r = KhoaPathfinding.findPath(start, goal, options);
			if (r.found()) {
				List<BlockPos> found = r.path();
				int gap = (int) Math.round(Math.sqrt(goal.distSqr(spawner)));
				return new Computed(found, List.of(), null, false,
					() -> "§aSafe path found: §f" + (found.size() - 1) + " blocks §7(" + label.apply(spawner) + ", ends " + gap + " from spawner)");
			}
			if (r.status() == KhoaPathfinding.Status.START_BLOCKED) {
				lastFailure = r.status();
				break; // nothing to search from: no goal can fix that
			}
			if (r.status() != KhoaPathfinding.Status.END_BLOCKED) {
				lastFailure = r.status();
				failed.add(goal);
				if (++tried >= maxGoalTries) {
					break;
				}
			}
		}

		final KhoaPathfinding.Status why = lastFailure;
		if (!heavy) {
			// light profile: no digging search, just show a straight line to the block
			return new Computed(List.of(), List.of(), spawner, false, () -> switch (why) {
				case START_BLOCKED -> "§cNo path: stand on solid ground first (straight line shown)";
				default -> "§6No walkable path to " + label.apply(spawner) + " - straight line shown";
			});
		}

		// no walkable path: find the route that needs the fewest blocks mined
		SpawnerDigPath.Result dig = SpawnerDigPath.find(level, start, spawner);
		if (dig != null && dig.route().size() > 1) {
			return new Computed(dig.route(), dig.mine(), null, false, () -> dig.mine().isEmpty()
				? "§aPath found: §f" + (dig.route().size() - 1) + " blocks §7(" + label.apply(spawner) + ")"
				: "§6No walkable path: §fmine " + dig.mine().size() + " blocks §7(" + (dig.route().size() - 1) + " long, " + label.apply(spawner) + ")");
		}
		return new Computed(List.of(), List.of(), null, true, () -> switch (why) {
			case START_BLOCKED -> "§cNo path: stand on solid ground first";
			case LIMIT -> "§cNo path found in time (too far or too complex)";
			default -> "§cNo path, even digging (too far, lava, or the loaded area ends)";
		});
	}

	private static boolean isInFailedPocket(BlockPos goal, List<BlockPos> failed) {
		for (BlockPos f : failed) {
			if (f.distSqr(goal) <= SKIP_POCKET_SQ) {
				return true;
			}
		}
		return false;
	}

	/** No path: forget the target so staring at this spawner again (after the countdown) calculates a fresh path. */
	private void fail() {
		path = List.of();
		mine = List.of();
		target = null;
		stareTicks = 0;
		shownSeconds = -1;
	}

	private static void say(Minecraft mc, String text) {
		mc.gui.hud.setOverlayMessage(Component.literal(text), false);
	}

	// ---- keep the path in sync with the player ----

	private void followAndRepath(Minecraft mc) {
		if (target == null || path.isEmpty() || mc.player == null) {
			return;
		}
		sinceRepath++;
		Vec3 pos = mc.player.position();
		int nearest = nearestIndex(pos);
		double offPath = Math.sqrt(centerOf(path.get(nearest)).distanceToSqr(pos));

		BlockPos end = path.get(path.size() - 1);
		if (centerOf(end).distanceToSqr(pos) < 2.25) {
			say(mc, "§aArrived");
			path = List.of();
			return;
		}
		if (!mine.isEmpty()) {
			int remaining = remainingMine(mc);
			if (remaining < lastRemaining) {
				lastRemaining = remaining;
				if (remaining == 0 && pending == null) {
					computePath(mc, target); // everything on the route is dug: a walkable path should exist now
				}
				return; // blocks left: keep the route, it only got shorter (searching again after every block froze the game)
			}
		}
		if (offPath > OFF_PATH_BLOCKS && sinceRepath >= REPATH_COOLDOWN) {
			computePath(mc, target); // wandered away from the line: calculate again from where you stand
		}
	}

	private int nearestIndex(Vec3 pos) {
		int best = 0;
		double bestSq = Double.MAX_VALUE;
		for (int i = 0; i < path.size(); i++) {
			double d = centerOf(path.get(i)).distanceToSqr(pos);
			if (d < bestSq) {
				bestSq = d;
				best = i;
			}
		}
		return best;
	}

	private static Vec3 centerOf(BlockPos p) {
		return new Vec3(p.getX() + 0.5, p.getY() + 0.15, p.getZ() + 0.5);
	}

	private int remainingMine(Minecraft mc) {
		int n = 0;
		for (BlockPos p : mine) {
			if (!mc.level.getBlockState(p).getCollisionShape(mc.level, p).isEmpty()) {
				n++;
			}
		}
		return n;
	}

	// ---- drawing ----

	/** Called from the world gizmo event (see SpawnerOverlay). Draws the part of the path that is still ahead of you. */
	public void render(Minecraft mc) {
		if (mc.player == null) {
			return;
		}
		if (directTo != null) {
			Gizmos.line(FinderMode.lineStart(mc), Vec3.atCenterOf(directTo), DIG_PATH_COLOR, PATH_WIDTH).setAlwaysOnTop();
			return;
		}
		if (path.size() < 2) {
			return;
		}
		int color = mine.isEmpty() ? PATH_COLOR : DIG_PATH_COLOR;
		int from = nearestIndex(mc.player.position());
		int to = Math.min(path.size(), from + MAX_DRAWN_POINTS);
		Vec3 previous = null;
		for (int i = from; i < to; i++) {
			Vec3 point = centerOf(path.get(i));
			if (previous != null) {
				Gizmos.line(previous, point, color, PATH_WIDTH).setAlwaysOnTop();
			}
			previous = point;
		}
		Gizmos.point(centerOf(path.get(path.size() - 1)).add(0, 0.4, 0), color, 12.0f).setAlwaysOnTop();

		int drawn = 0;
		for (BlockPos p : mine) {
			if (mc.level.getBlockState(p).getCollisionShape(mc.level, p).isEmpty()) {
				continue; // already mined
			}
			Gizmos.cuboid(p, GizmoStyle.stroke(MINE_COLOR, PATH_WIDTH)).setAlwaysOnTop();
			if (++drawn >= MAX_DRAWN_MINE) {
				break;
			}
		}
	}
}
