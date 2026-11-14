package khoa.tools.client.feature.waypoints;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import khoa.api.color.KhoaBlockColor;
import khoa.tools.client.config.KhoaConfig;

/**
 * The area editing mode: the GUI is hidden and you walk around drawing the regions of an area waypoint.
 * <ul>
 * <li>hold right click, move, release: a box between the block you pressed on and the block you released on (click without moving = one block)</li>
 * <li>press inside a region you already drew: its nearest corner follows your crosshair, release to resize (the opposite corner stays)</li>
 * <li>regions swallowed completely by the new / resized box are removed (they count as part of it)</li>
 * <li>Enter saves everything into the waypoint, Backspace / Ctrl+Z undo, Delete removes the region you look at, Esc cancels</li>
 * </ul>
 * Everything drawn so far is previewed (always on top, so no outline is ever missing) and the waypoint board shows over the middle of the bounding box.
 */
final class AreaEditSession {
	private static final int MAX_REGIONS = 64;

	private static boolean active;
	private static WaypointEditScreen screen;
	private static String editingId;
	private static Waypoint ghost;
	private static int rgb;

	private static final List<int[]> REGIONS = new ArrayList<>();
	private static final Deque<List<int[]>> UNDO = new ArrayDeque<>();

	private static boolean dragging;
	private static int resizeIndex = -1;
	private static int[] fixed;
	private static int[] end;
	private static boolean clamped;

	private static boolean wasUse;
	private static boolean wasEnter;
	private static boolean wasUndo;
	private static boolean wasDelete;
	private static String note = "";
	private static long noteUntil;

	private static int version;
	private static int cachedVersion = -1;
	private static RegionGeometry.Result cachedGeometry;
	private static int[] cachedBounds;

	private AreaEditSession() {
	}

	static boolean active() {
		return active;
	}

	/** Id of the waypoint being edited: its saved overlay / board is hidden while the session shows the live version. */
	static String editingId() {
		return active ? editingId : null;
	}

	/** The waypoint as it is right now in the session (for the board arrow), or null while nothing is drawn. */
	static Waypoint ghost() {
		return active && ghost != null && !REGIONS.isEmpty() ? ghost : null;
	}

	static void begin(WaypointEditScreen from, String waypointId, String name, int color, List<int[]> initial) {
		Minecraft mc = Minecraft.getInstance();
		screen = from;
		editingId = waypointId;
		rgb = color;
		REGIONS.clear();
		for (int[] r : initial) {
			REGIONS.add(r.clone());
		}
		UNDO.clear();
		dragging = false;
		resizeIndex = -1;
		fixed = null;
		end = null;
		wasUse = mc.options.keyUse.isDown();
		wasEnter = enterDown();
		wasUndo = true;
		wasDelete = true;
		note = "";
		ghost = new Waypoint();
		ghost.id = waypointId == null ? "area-editor" : waypointId;
		ghost.name = name;
		ghost.rgb = color;
		ghost.dim = mc.level == null ? ghost.dim : WaypointsFeature.dimension(mc);
		touch();
		active = true;
		mc.gui.setScreen(null);
	}

	private static void touch() {
		version++;
		if (ghost != null) {
			ghost.setRegions(REGIONS);
		}
	}

	// ------------------------------------------------------------------ input (called every client tick)

	private static boolean enterDown() {
		return InputConstants.isKeyDown(InputConstants.KEY_RETURN) || InputConstants.isKeyDown(InputConstants.KEY_NUMPADENTER);
	}

	private static BlockPos aim(Minecraft mc) {
		double reach = Math.max(5, KhoaConfig.getInt(WaypointsFeature.ID, "area_reach", 24));
		HitResult hit = mc.player.pick(reach, 1.0F, false);
		return hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK ? block.getBlockPos() : null;
	}

	private static int maxSide() {
		return Math.max(1, KhoaConfig.getInt(WaypointsFeature.ID, "area_max", 100));
	}

	static void tick(Minecraft mc) {
		if (!active) {
			return;
		}
		if (mc.level == null || mc.player == null) {
			active = false;
			return;
		}
		if (mc.gui.screen() != null) { // Esc or any menu: cancel and show the editor again, nothing changed
			cancel(mc);
			return;
		}
		boolean enter = enterDown();
		if (enter && !wasEnter) {
			wasEnter = true;
			finish(mc);
			return;
		}
		wasEnter = enter;

		boolean ctrl = InputConstants.isKeyDown(InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL);
		boolean undoKey = InputConstants.isKeyDown(InputConstants.KEY_BACKSPACE) || (ctrl && InputConstants.isKeyDown(InputConstants.KEY_Z));
		if (undoKey && !wasUndo) {
			undo();
		}
		wasUndo = undoKey;
		boolean delete = InputConstants.isKeyDown(InputConstants.KEY_DELETE);
		if (delete && !wasDelete) {
			removeLookedAt(aim(mc));
		}
		wasDelete = delete;

		boolean down = mc.options.keyUse.isDown();
		BlockPos aimed = aim(mc);
		if (down && !wasUse) {
			press(aimed);
		} else if (down && dragging) {
			if (aimed != null) {
				end = new int[] { aimed.getX(), aimed.getY(), aimed.getZ() };
			}
		} else if (!down && wasUse && dragging) {
			if (aimed != null) {
				end = new int[] { aimed.getX(), aimed.getY(), aimed.getZ() };
			}
			release();
		}
		wasUse = down;
	}

	private static int regionAt(BlockPos p) {
		for (int i = REGIONS.size() - 1; i >= 0; i--) {
			int[] r = REGIONS.get(i);
			if (p.getX() >= r[0] && p.getX() <= r[3] && p.getY() >= r[1] && p.getY() <= r[4] && p.getZ() >= r[2] && p.getZ() <= r[5]) {
				return i;
			}
		}
		return -1;
	}

	private static void press(BlockPos p) {
		if (p == null) {
			return;
		}
		int[] at = { p.getX(), p.getY(), p.getZ() };
		int inside = regionAt(p);
		if (inside >= 0) { // press inside a region: grab its nearest corner, the opposite one stays
			int[] r = REGIONS.get(inside);
			int bestMask = 0;
			double best = Double.MAX_VALUE;
			for (int mask = 0; mask < 8; mask++) {
				double d = 0;
				for (int k = 0; k < 3; k++) {
					double c = (mask >> k & 1) == 1 ? r[k + 3] : r[k];
					d += (c - at[k]) * (c - at[k]);
				}
				if (d < best) {
					best = d;
					bestMask = mask;
				}
			}
			fixed = new int[3];
			for (int k = 0; k < 3; k++) {
				fixed[k] = (bestMask >> k & 1) == 1 ? r[k] : r[k + 3];
			}
			resizeIndex = inside;
		} else {
			resizeIndex = -1;
			fixed = at;
		}
		end = at;
		dragging = true;
	}

	/** The box the current drag would make, every side limited to the maximum size. */
	private static int[] preview() {
		int max = maxSide();
		int[] box = new int[6];
		clamped = false;
		for (int k = 0; k < 3; k++) {
			int to = end[k];
			if (Math.abs(to - fixed[k]) + 1 > max) {
				to = fixed[k] + (to > fixed[k] ? 1 : -1) * (max - 1);
				clamped = true;
			}
			box[k] = Math.min(fixed[k], to);
			box[k + 3] = Math.max(fixed[k], to);
		}
		return box;
	}

	private static void release() {
		int[] box = preview();
		dragging = false;
		if (resizeIndex < 0 && REGIONS.size() >= MAX_REGIONS) {
			say("Too many regions in one waypoint (max " + MAX_REGIONS + ").");
			return;
		}
		snapshot();
		int placed;
		if (resizeIndex >= 0) {
			REGIONS.set(resizeIndex, box);
			placed = resizeIndex;
		} else {
			REGIONS.add(box);
			placed = REGIONS.size() - 1;
		}
		int removed = 0;
		for (int i = REGIONS.size() - 1; i >= 0; i--) { // swallowed regions become part of the new one
			int[] r = REGIONS.get(i);
			if (i != placed && r[0] >= box[0] && r[1] >= box[1] && r[2] >= box[2] && r[3] <= box[3] && r[4] <= box[4] && r[5] <= box[5]) {
				REGIONS.remove(i);
				removed++;
				if (i < placed) {
					placed--;
				}
			}
		}
		resizeIndex = -1;
		if (removed > 0) {
			say("Merged " + removed + " region" + (removed == 1 ? "" : "s") + " into the new box.");
		}
		touch();
	}

	private static void snapshot() {
		List<int[]> copy = new ArrayList<>();
		for (int[] r : REGIONS) {
			copy.add(r.clone());
		}
		UNDO.push(copy);
		while (UNDO.size() > 100) {
			UNDO.removeLast();
		}
	}

	private static void undo() {
		if (dragging) {
			dragging = false;
			return;
		}
		if (UNDO.isEmpty()) {
			say("Nothing to undo.");
			return;
		}
		REGIONS.clear();
		REGIONS.addAll(UNDO.pop());
		touch();
	}

	private static void removeLookedAt(BlockPos p) {
		if (p == null) {
			return;
		}
		int i = regionAt(p);
		if (i >= 0) {
			snapshot();
			REGIONS.remove(i);
			touch();
		}
	}

	private static void say(String text) {
		note = text;
		noteUntil = System.currentTimeMillis() + 3000;
	}

	private static void finish(Minecraft mc) {
		active = false;
		List<int[]> result = new ArrayList<>();
		for (int[] r : REGIONS) {
			result.add(r.clone());
		}
		WaypointEditScreen target = screen;
		screen = null;
		target.applyRegions(result);
		mc.gui.setScreen(target);
	}

	private static void cancel(Minecraft mc) {
		active = false;
		WaypointEditScreen target = screen;
		screen = null;
		mc.gui.setScreen(target);
	}

	// ------------------------------------------------------------------ drawing

	/** World preview: everything drawn so far plus the box you are dragging, always on top. */
	static void draw(Vec3 eye) {
		if (!active) {
			return;
		}
		List<int[]> shown = new ArrayList<>();
		for (int i = 0; i < REGIONS.size(); i++) {
			if (!(dragging && i == resizeIndex)) { // the region being resized is replaced by the preview
				shown.add(REGIONS.get(i));
			}
		}
		if (!shown.isEmpty()) {
			if (cachedVersion != version || dragging) {
				cachedGeometry = RegionGeometry.build(shown, List.of());
				cachedBounds = boundsOf(shown);
				cachedVersion = dragging ? -1 : version;
			}
			AreaRender.drawEditing(cachedGeometry, cachedBounds, rgb, eye, 0x40);
		}
		if (dragging && end != null && fixed != null) {
			int[] box = preview();
			int color = clamped ? 0xFF5555 : KhoaBlockColor.brighten(rgb, 0.55F);
			AreaRender.drawEditing(RegionGeometry.build(List.of(box), List.of()), box, color, eye, 0x58);
		}
	}

	private static int[] boundsOf(List<int[]> list) {
		int[] b = list.get(0).clone();
		for (int[] r : list) {
			for (int k = 0; k < 3; k++) {
				b[k] = Math.min(b[k], r[k]);
				b[k + 3] = Math.max(b[k + 3], r[k + 3]);
			}
		}
		return b;
	}

	/** Controls reminder and the size of the box being dragged. */
	static void renderHud(GuiGraphicsExtractor g, Minecraft mc) {
		if (!active) {
			return;
		}
		int cx = mc.getWindow().getGuiScaledWidth() / 2;
		String head = "AREA EDIT   " + REGIONS.size() + " region" + (REGIONS.size() == 1 ? "" : "s");
		if (dragging && end != null && fixed != null) {
			int[] box = preview();
			head += String.format(Locale.ROOT, "   |   box %d x %d x %d%s", box[3] - box[0] + 1, box[4] - box[1] + 1, box[5] - box[2] + 1,
				clamped ? "  (max " + maxSide() + " per side)" : "");
		}
		g.centeredText(mc.font, head, cx, 8, clamped && dragging ? 0xFFFF5555 : 0xFFFFFFFF);
		g.centeredText(mc.font, "Hold right click and release to draw a box (a quick click = one block)  |  press inside a box to resize it", cx, 20, 0xFFCCCCCC);
		g.centeredText(mc.font, "Enter: save   Backspace / Ctrl+Z: undo   Delete: remove the box you look at   Esc: cancel", cx, 31, 0xFFCCCCCC);
		if (System.currentTimeMillis() < noteUntil) {
			g.centeredText(mc.font, note, cx, 45, 0xFFFFDD66);
		}
	}
}
