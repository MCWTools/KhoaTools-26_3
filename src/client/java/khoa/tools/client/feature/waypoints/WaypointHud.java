package khoa.tools.client.feature.waypoints;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.world.phys.Vec3;

import khoa.tools.client.config.KhoaConfig;

/** The board: a small coloured arrow with the waypoint's letter above each waypoint. Look near one and it grows and shows name + distance. */
final class WaypointHud {
	private static final float BASE = 0.55F;
	private static final float ZOOMED = 0.9F;
	/** Pixels (GUI scaled) from the crosshair within which a waypoint counts as looked at. */
	private static final double FOCUS_RADIUS = 26;

	private static final Map<String, Float> SCALES = new HashMap<>();
	private static final Map<String, Float> OFFSETS = new HashMap<>();
	private static final Map<String, Float> OFFSETS_X = new HashMap<>();
	private static final Set<String> STACKED = new HashSet<>();
	private static final Map<String, Float> FACTORS = new HashMap<>();
	private static long lastFrame;
	private static String focusId;
	/** How much the boards are enlarged because the camera is zoomed in (1 = no zoom). */
	private static float zoomMul = 1F;

	private WaypointHud() {
	}

	private record Spot(Waypoint wp, float x, float y, double distance) {
	}

	static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null && mc.player != null) {
			AreaEditSession.renderHud(g, mc);
		}
		if (mc.level == null || mc.player == null || !WaypointsFeature.show() || mc.gui.hud.isHidden()) {
			return;
		}
		if (mc.gui.screen() != null && !(mc.gui.screen() instanceof ChatScreen)) {
			return;
		}
		WorldData data = WaypointStore.world(mc);
		// while the area editor is open its live version replaces the saved waypoint being edited
		Waypoint ghost = AreaEditSession.ghost();
		String editing = AreaEditSession.editingId();
		List<Waypoint> list = new java.util.ArrayList<>();
		for (Waypoint saved : data.waypoints) {
			if (!saved.id.equals(editing)) {
				list.add(saved);
			}
		}
		if (ghost != null) {
			list.add(ghost);
		}
		if (list.isEmpty()) {
			return;
		}
		String dim = WaypointsFeature.dimension(mc);
		int maxDist = KhoaConfig.getInt(WaypointsFeature.ID, "distance", 0);
		double maxDistance = maxDist > 0 ? maxDist : Double.MAX_VALUE;

		Camera camera = mc.gameRenderer.mainCamera();
		Vec3 eye = camera.position();
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();
		// the camera's own view * projection matrix, so zoom mods and FOV effects are followed exactly
		org.joml.Matrix4f viewProj = camera.getViewRotationProjectionMatrix(new org.joml.Matrix4f());
		org.joml.Vector4f clip = new org.joml.Vector4f();
		// Zoom: with a narrower FOV than your FOV setting the world is magnified, and the boards grow with it (so they keep their size
		// relative to the scene instead of staying a tiny fixed number of pixels). Not smaller than normal when the FOV is wider.
		Vec3 ahead = vec(camera.forwardVector()).add(vec(camera.upVector()));
		viewProj.transform((float) ahead.x, (float) ahead.y, (float) ahead.z, 1F, clip);
		double curScale = clip.w > 0.01F ? clip.y / clip.w : 0;
		double refScale = 1.0 / Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
		zoomMul = curScale > 0 && refScale > 0 ? (float) Math.max(1.0, Math.min(4.0, curScale / refScale)) : 1F;
		final float zm = zoomMul;

		List<Spot> spots = new java.util.ArrayList<>();
		for (Waypoint wp : list) {
			if (!wp.dim.equals(dim) || (wp != ghost && !data.visible(wp))) {
				continue;
			}
			double distance = wp.distanceTo(mc.player.getX(), mc.player.getY(), mc.player.getZ());
			if (distance > maxDistance) {
				continue;
			}
			// single block: just above it; area: above the middle of its bounding box
			Vec3 anchor = wp.boardAnchor();
			Vec3 d = anchor.subtract(eye);
			viewProj.transform((float) d.x, (float) d.y, (float) d.z, 1F, clip);
			if (!(clip.w > 0.05F)) {
				continue; // behind you (or the matrix is not usable)
			}
			float sx = (clip.x / clip.w * 0.5F + 0.5F) * w;
			float sy = (0.5F - clip.y / clip.w * 0.5F) * h;
			if (sx < -40 || sx > w + 40 || sy < -40 || sy > h + 40) {
				continue;
			}
			spots.add(new Spot(wp, sx, sy, distance));
		}

		long now = System.nanoTime();
		double dt = lastFrame == 0 ? 0.016 : Math.min(0.1, (now - lastFrame) / 1e9);
		lastFrame = now;
		float blend = (float) (1 - Math.exp(-dt * 18));
		float slow = (float) (1 - Math.exp(-dt * 12));

		// Boards that sit on top of each other are stacked: the nearest keeps its place, the next ones go above and below it in turn,
		// each a bit smaller (down to 70%). When they no longer overlap they glide back to normal.
		spots.sort(java.util.Comparator.comparingDouble(Spot::distance));
		List<List<Spot>> clusters = new java.util.ArrayList<>();
		for (Spot sp : spots) {
			List<Spot> home = null;
			for (List<Spot> c : clusters) {
				Spot leader = c.get(0);
				// stack only when almost nothing of the farther board would show (a few pixels); once stacked, keep it until clearly apart
				float limit = (STACKED.contains(sp.wp.id) || STACKED.contains(leader.wp.id) ? 9F : 4.5F) * zm;
				if (Math.abs(leader.x - sp.x) <= limit && Math.abs(leader.y - sp.y) <= limit) {
					home = c;
					break;
				}
			}
			if (home == null) {
				home = new java.util.ArrayList<>();
				clusters.add(home);
			}
			home.add(sp);
		}
		Map<String, float[]> goal = new HashMap<>(); // id -> {vertical offset, size factor, horizontal offset}
		STACKED.clear();
		for (List<Spot> c : clusters) {
			Spot leader = c.get(0);
			goal.put(leader.wp.id, new float[] { 0F, 1F, 0F });
			// Column through the nearest board: the next ones go above and below it in turn, each a little smaller (not below 70%).
			// Every board is placed from the edge of the previous one on its side, so none ends up squeezed between two others.
			float aboveEdge = 0F; // tip offset of the last board placed above
			float aboveH = WaypointBadge.HEIGHT * BASE * zm; // its height
			float belowEdge = 0F;
			for (int i = 1; i < c.size(); i++) {
				Spot member = c.get(i);
				float factor = Math.max(0.7F, 1F - 0.08F * i);
				float height = WaypointBadge.HEIGHT * BASE * factor * zm;
				float offset;
				if (i % 2 == 1) { // above
					offset = aboveEdge - aboveH * 0.8F;
					aboveEdge = offset;
					aboveH = height;
				} else { // below
					offset = belowEdge + height * 0.8F;
					belowEdge = offset;
				}
				goal.put(member.wp.id, new float[] { offset, factor, leader.x - member.x });
			}
			if (c.size() > 1) {
				for (Spot member : c) {
					STACKED.add(member.wp.id);
				}
			}
		}
		List<Spot> shown = new java.util.ArrayList<>();
		Set<String> alive = new HashSet<>();
		for (Spot sp : spots) {
			String id = sp.wp.id;
			alive.add(id);
			float[] want = goal.get(id);
			float offset = OFFSETS.getOrDefault(id, want[0]);
			offset += (want[0] - offset) * slow;
			OFFSETS.put(id, offset);
			float side = OFFSETS_X.getOrDefault(id, want[2]);
			side += (want[2] - side) * slow;
			OFFSETS_X.put(id, side);
			shown.add(new Spot(sp.wp, sp.x + side, sp.y + offset, sp.distance));
		}
		OFFSETS.keySet().retainAll(alive);
		OFFSETS_X.keySet().retainAll(alive);

		// Looked at = the badge whose middle is closest to the crosshair. Where badges are close together the reach of each one shrinks
		// (half the gap to its neighbour), and the current one is kept until another is clearly closer, so it does not flicker.
		Spot focus = null;
		if (KhoaConfig.getBool(WaypointsFeature.ID, "zoom", true)) {
			double cx = w / 2.0;
			double cy = h / 2.0;
			double[] mx = new double[shown.size()];
			double[] my = new double[shown.size()];
			for (int i = 0; i < shown.size(); i++) {
				Spot sp = shown.get(i);
				mx[i] = sp.x;
				my[i] = sp.y - WaypointBadge.HEIGHT * SCALES.getOrDefault(sp.wp.id, BASE) * zm / 2.0;
			}
			double bestDist = Double.MAX_VALUE;
			double keptDist = Double.MAX_VALUE;
			Spot best = null;
			Spot kept = null;
			for (int i = 0; i < shown.size(); i++) {
				double gap = Double.MAX_VALUE;
				for (int j = 0; j < shown.size(); j++) {
					if (j != i) {
						gap = Math.min(gap, Math.hypot(mx[i] - mx[j], my[i] - my[j]));
					}
				}
				double reach = Math.max(7 * zm, Math.min(FOCUS_RADIUS * zm, gap / 2));
				double dist = Math.hypot(mx[i] - cx, my[i] - cy);
				if (dist > reach) {
					continue;
				}
				if (dist < bestDist) {
					bestDist = dist;
					best = shown.get(i);
				}
				if (shown.get(i).wp.id.equals(focusId)) {
					kept = shown.get(i);
					keptDist = dist;
				}
			}
			focus = kept != null && best != null && keptDist - bestDist < 3 ? kept : best;
		}
		focusId = focus == null ? null : focus.wp.id;
		Map<String, Float> drawScale = new HashMap<>();
		for (Spot sp : shown) {
			String id = sp.wp.id;
			float factor = sp == focus ? 1F : goal.get(id)[1];
			float f = FACTORS.getOrDefault(id, factor);
			f += (factor - f) * blend;
			FACTORS.put(id, f);
			float target = sp == focus ? ZOOMED : BASE;
			float scale = SCALES.getOrDefault(id, BASE);
			scale += (target - scale) * blend;
			SCALES.put(id, scale);
			drawScale.put(id, scale * f * zm);
		}
		SCALES.keySet().retainAll(alive);
		FACTORS.keySet().retainAll(alive);

		for (int i = shown.size() - 1; i >= 0; i--) { // far to near, so the nearest is on top
			Spot sp = shown.get(i);
			if (sp != focus) {
				draw(g, mc, sp, drawScale.get(sp.wp.id));
			}
		}
		if (focus != null) {
			draw(g, mc, focus, drawScale.get(focus.wp.id)); // on top of everything
		}
	}

	private static void draw(GuiGraphicsExtractor g, Minecraft mc, Spot s, float scale) {
		Waypoint wp = s.wp;
		WaypointBadge.draw(g, mc.font, s.x, s.y, scale, wp.rgb, wp.initial());
		// text fades in while the board grows from its normal size to the looked-at size
		float grow = (scale / zoomMul - BASE) / (ZOOMED - BASE);
		if (grow < 0.3F) {
			return;
		}
		float fade = Math.min(1F, (grow - 0.3F) / 0.5F);
		int alpha = (int) (255 * fade) << 24;
		if (alpha == 0) {
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(s.x, s.y);
		g.pose().scale(0.8F * zoomMul, 0.8F * zoomMul);
		String title = wp.title();
		g.centeredText(mc.font, title, 0, -WaypointBadge.HEIGHT - 12, alpha | 0xFFFFFF);
		g.centeredText(mc.font, formatDistance(s.distance), 0, 4, alpha | 0xFFFFFF);
		String timer = timerText(mc, wp);
		if (timer != null) {
			g.centeredText(mc.font, timer, 0, 15, alpha | 0xFFDD66);
		}
		g.pose().popMatrix();
	}

	static String formatDistance(double blocks) {
		return blocks >= 1000 ? String.format(Locale.ROOT, "%.1f km", blocks / 1000) : Math.round(blocks) + " m";
	}

	/** "Items despawn in 4:32" while you are near, "paused" when too far, null for normal waypoints. */
	static String timerText(Minecraft mc, Waypoint wp) {
		if (!wp.death || wp.ticksLeft < 0) {
			return null;
		}
		if (wp.ticksLeft == 0) {
			return "Items despawned";
		}
		String time = clock(wp.ticksLeft);
		return WaypointsFeature.timerNear(mc, wp) && wp.dim.equals(WaypointsFeature.dimension(mc)) ? "Items despawn in " + time : "Timer paused " + time;
	}

	static String clock(int ticks) {
		int seconds = (ticks + 19) / 20;
		return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
	}

	private static Vec3 vec(org.joml.Vector3fc v) {
		return new Vec3(v.x(), v.y(), v.z());
	}
}
