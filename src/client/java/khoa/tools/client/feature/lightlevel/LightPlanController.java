package khoa.tools.client.feature.lightlevel;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import khoa.tools.KhoaTools;

/** Decides when to plan again, runs the planner off the client thread and draws the planned spots. */
final class LightPlanController {
	/** Wait until the surfaces stayed the same for this many ticks (lets the light engine finish)... */
	private static final int QUIET_TICKS = 8;
	/** ...but never longer than this when the world keeps changing. */
	private static final int MAX_WAIT_TICKS = 40;

	private static final int NEXT = ARGB.color(255, 255, 235, 40);
	private static final int OTHER = ARGB.color(255, 40, 235, 255);

	private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "KhoaTools light planner");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});

	private LightPlan.State state;
	private LightScan.Snapshot latest;
	private Future<LightPlan.State> job;
	/** {@link #latest} differs from what was planned last. */
	private boolean fresh;
	private int quiet;
	private int waited;
	private int rounds;
	private volatile long planMillis;
	private String lastMessage = "";

	/** Throw the plan away and make a new one from nothing. */
	void restart() {
		if (job != null) {
			job.cancel(true);
			job = null;
		}
		state = null;
		fresh = latest != null;
		quiet = QUIET_TICKS;
		lastMessage = "";
	}

	/** Forget everything, also the scanned world. */
	void clear() {
		latest = null;
		restart();
	}

	/** A new scan of the world. */
	void offer(LightScan.Snapshot snapshot) {
		boolean other = latest != null && !latest.settings().equals(snapshot.settings());
		if (other) {
			restart();
		}
		if (latest == null || other || !latest.area().equals(snapshot.area()) || latest.originY() != snapshot.originY()
			|| !Arrays.equals(latest.dark(), snapshot.dark()) || !Arrays.equals(latest.candidates(), snapshot.candidates())) {
			fresh = true;
			quiet = 0;
		}
		latest = snapshot;
	}

	void tick(Minecraft mc) {
		if (job != null && job.isDone()) {
			try {
				state = job.get();
				rounds++;
				announce(mc);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (ExecutionException e) {
				KhoaTools.LOGGER.error("Light placement planning failed", e.getCause());
				mc.gui.hud.setOverlayMessage(Component.literal("Light plan failed, see the log"), false);
			}
			job = null;
		}

		if (!fresh) {
			waited = 0;
			return;
		}
		quiet++;
		waited++;
		if (job == null && (quiet >= QUIET_TICKS || waited >= MAX_WAIT_TICKS)) {
			LightScan.Snapshot snapshot = latest;
			LightPlan.State previous = state;
			long seed = rounds * 31L + 7;
			job = worker.submit(() -> {
				long start = System.nanoTime();
				LightPlan.State result = LightPlan.update(snapshot, previous, seed);
				planMillis = (System.nanoTime() - start) / 1_000_000;
				return result;
			});
			if (previous == null) {
				mc.gui.hud.setOverlayMessage(Component.literal("Light plan: calculating..."), false);
			}
			fresh = false;
			waited = 0;
		}
	}

	/** Time the last finished round took. */
	long planMillis() {
		return planMillis;
	}

	private void announce(Minecraft mc) {
		String text;
		if (state.settings().threshold() >= state.settings().emission()) {
			text = "Light plan: this source cannot light anything above level " + state.settings().threshold();
		} else if (state.spots().isEmpty()) {
			text = state.uncoverable() == 0 ? "Light plan: area is done" : "Light plan: " + state.uncoverable() + " dark block(s) cannot be lit";
		} else {
			text = "Light plan: " + state.spots().size() + " source(s) left"
				+ (state.uncoverable() == 0 ? "" : ", " + state.uncoverable() + " dark block(s) cannot be lit");
		}
		if (!text.equals(lastMessage)) {
			lastMessage = text;
			mc.gui.hud.setOverlayMessage(Component.literal(text), false);
		}
	}

	/** True when the last change has been planned. */
	boolean idle() {
		return state != null && !fresh && job == null;
	}

	/** Number of finished planning rounds (never reset). */
	int rounds() {
		return rounds;
	}

	LightPlan.State state() {
		return state;
	}

	void draw(Minecraft mc) {
		if (state == null || mc.level == null || mc.player == null) {
			return;
		}
		List<LightPlan.Spot> spots = state.spots();
		Vec3 feet = mc.player.position();
		LightPlan.Spot nearest = null;
		double best = Double.MAX_VALUE;
		for (LightPlan.Spot spot : spots) {
			double distance = Vec3.atCenterOf(spot.pos()).distanceToSqr(feet);
			if (distance < best && !placed(mc, spot)) {
				best = distance;
				nearest = spot;
			}
		}
		for (LightPlan.Spot spot : spots) {
			if (placed(mc, spot)) {
				continue;
			}
			BlockPos p = spot.pos();
			int color = spot == nearest ? NEXT : OTHER;
			double x = p.getX();
			double y = p.getY();
			double z = p.getZ();
			// A solid pad standing just above the red overlay of the same surface, drawn over it.
			Gizmos.cuboid(new AABB(x + 0.04, y + 0.02, z + 0.04, x + 0.96, y + 0.14, z + 0.96), GizmoStyle.fill(ARGB.color(215, color)))
				.setAlwaysOnTop();
			Gizmos.cuboid(new AABB(x + 0.02, y + 0.02, z + 0.02, x + 0.98, y + 0.98, z + 0.98), GizmoStyle.stroke(color, 2.5F)).setAlwaysOnTop();
			Gizmos.line(new Vec3(x + 0.5, y + 0.98, z + 0.5), new Vec3(x + 0.5, y + 2.2, z + 0.5), color, 3.0F).setAlwaysOnTop();
			Gizmos.billboardText(Integer.toString(spot.covers().length), new Vec3(x + 0.5, y + 2.45, z + 0.5),
				TextGizmo.Style.forColorAndCentered(color)).setAlwaysOnTop();
		}
	}

	/** A source is already standing there; the next round removes the spot. */
	private boolean placed(Minecraft mc, LightPlan.Spot spot) {
		return mc.level.getBlockState(spot.pos()).getLightEmission() >= state.settings().emission();
	}
}
