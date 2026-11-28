package khoa.tools.client.feature.lightlevel;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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
	/** Wait until nothing changed for this many ticks (lets the light engine finish)... */
	private static final int QUIET_TICKS = 5;
	/** ...but never longer than this when the world keeps changing. */
	private static final int MAX_WAIT_TICKS = 30;
	/** The planned area follows the player in steps, so small movements do not reshuffle anything. */
	private static final int MOVE_X = 6;
	private static final int MOVE_Y = 3;

	private static final int NEXT = ARGB.color(255, 255, 235, 40);
	private static final int OTHER = ARGB.color(255, 40, 235, 255);

	private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "KhoaTools light planner");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});

	private ClientLevel level;
	private LightPlan.State state;
	private LightPlan.Region region;
	private LightPlan.Settings settings;
	private Future<LightPlan.State> job;
	private boolean pending;
	private int quiet;
	private int waited;
	private int rounds;
	private String lastMessage = "";

	/** Forget everything; the next tick plans from nothing. */
	void reset() {
		if (job != null) {
			job.cancel(true);
			job = null;
		}
		state = null;
		region = null;
		settings = null;
		pending = false;
		lastMessage = "";
	}

	void worldChanged() {
		pending = true;
		quiet = 0;
	}

	void tick(Minecraft mc, LightPlan.Settings wanted, int radius, int height) {
		if (mc.level != level) {
			reset();
			level = mc.level;
		}
		if (mc.level == null || mc.player == null) {
			return;
		}
		if (settings != null && !settings.equals(wanted)) {
			reset();
		}
		settings = wanted;

		BlockPos here = mc.player.blockPosition();
		if (region == null || region.radius() != radius || region.height() != height || Math.abs(here.getX() - region.x()) >= MOVE_X
			|| Math.abs(here.getZ() - region.z()) >= MOVE_X || Math.abs(here.getY() - 1 - region.y()) >= MOVE_Y) {
			region = new LightPlan.Region(here.getX(), here.getY() - 1, here.getZ(), radius, height);
			pending = true;
			quiet = QUIET_TICKS;
		}

		if (job != null && job.isDone()) {
			try {
				state = job.get();
				rounds++;
				announce(mc);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (ExecutionException e) {
				KhoaTools.LOGGER.error("Light placement planning failed", e.getCause());
				KhoaTools.LOGGER.error("Light placement settings: {} {}", settings, region);
				mc.gui.hud.setOverlayMessage(Component.literal("Light plan failed, see the log"), false);
			}
			job = null;
		}

		if (!pending) {
			return;
		}
		quiet++;
		waited++;
		if (job == null && (quiet >= QUIET_TICKS || waited >= MAX_WAIT_TICKS)) {
			LightPlan.Snapshot snapshot = LightPlan.capture(mc.level, region, settings);
			LightPlan.State previous = state;
			long seed = rounds * 31L + 7;
			job = worker.submit(() -> LightPlan.update(snapshot, previous, seed));
			pending = false;
			waited = 0;
		}
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
		return state != null && !pending && job == null;
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
