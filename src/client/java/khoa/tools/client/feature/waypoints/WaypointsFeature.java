package khoa.tools.client.feature.waypoints;

import java.util.List;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import com.mojang.blaze3d.vertex.PoseStack;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import khoa.tools.KhoaTools;
import khoa.api.color.KhoaBlockColor;
import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.config.KhoaKeybinds;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.setting.Setting;

/**
 * Waypoints: a manager GUI (create / edit / group / delete / import from Xaero), single-block and area waypoints, a highlighted block (or one box
 * for an area) plus a small board arrow with the waypoint's letter, zoom + distance when you look at one, automatic death points with a 5 minute
 * item despawn countdown that only runs while you are near, and the same waypoints on Xaero's minimap and world map.
 */
public final class WaypointsFeature implements KhoaFeature {
	static final String ID = "waypoints";

	/** The block you were aiming at when the GUI was requested (used by the Create screen). */
	static BlockPos aimed;
	private static boolean openNext;
	private static boolean wasDead;
	private static int saveTimer;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Waypoints";
	}

	@Override
	public String description() {
		return "Click the card to open the waypoint manager: single-block or area waypoints, groups, colours, sorting, multi-select and Xaero import. "
			+ "Waypoints get a highlighted block and a board arrow; look at one to zoom it and see the distance. Deaths are saved automatically with an item despawn timer. "
			+ "Files: .minecraft/khoatools/waypoints/<server or world>/Waypoints.json + Groups.json (+ backup folder).";
	}

	@Override
	public List<Setting> settings() {
		return List.of(
			Setting.action("open", "Waypoint manager", "Open the waypoint list.", "Open", () -> activate(Minecraft.getInstance())),
			Setting.bool("visible", "Show waypoints", "Draw the board arrows and block highlights.", () -> KhoaConfig.getBool(ID, "visible", true), v -> KhoaConfig.put(ID, "visible", v)),
			Setting.bool("overlay", "Highlight block", "Fill + outline the block / box of each waypoint (not through walls).", () -> KhoaConfig.getBool(ID, "overlay", true), v -> KhoaConfig.put(ID, "overlay", v)),
			Setting.bool("zoom", "Zoom when looked at", "Look near a waypoint to enlarge it and show its name and distance.", () -> KhoaConfig.getBool(ID, "zoom", true), v -> KhoaConfig.put(ID, "zoom", v)),
			Setting.bool("convert", "Convert coord when copy to nether/overworld", "Ctrl+C / Ctrl+V in the waypoint list between Overworld and Nether multiplies or divides X and Z by 8 so the spot matches. Off = same numbers.", () -> KhoaConfig.getBool(ID, "convert", true), v -> KhoaConfig.put(ID, "convert", v)),
			Setting.bool("beacon", "Beacon light", "Draw a coloured light beam from the bottom of the world to the sky at every waypoint. It stays visible from any distance.", () -> KhoaConfig.getBool(ID, "beacon", false), v -> KhoaConfig.put(ID, "beacon", v)),
			Setting.bool("xaero_map", "Show on Xaero maps", "Also show these waypoints on Xaero's minimap and world map (only when those mods are installed; current dimension only).", () -> KhoaConfig.getBool(ID, "xaero_map", true), v -> KhoaConfig.put(ID, "xaero_map", v)),
			Setting.slider("distance", "Max distance (0 = no limit)", "Waypoints farther than this many blocks are hidden.", 0, 5000, () -> KhoaConfig.getInt(ID, "distance", 0), v -> KhoaConfig.put(ID, "distance", v)),
			Setting.slider("area_reach", "Area editor reach (blocks)", "How far you can point at a block while drawing an area.", 5, 64, () -> KhoaConfig.getInt(ID, "area_reach", 24), v -> KhoaConfig.put(ID, "area_reach", v)),
			Setting.slider("area_max", "Area max size per side", "The longest side one box of an area may have.", 4, 128, () -> KhoaConfig.getInt(ID, "area_max", 100), v -> KhoaConfig.put(ID, "area_max", v)),
			Setting.bool("death", "Death waypoints", "Create a waypoint (skull) where you die.", () -> KhoaConfig.getBool(ID, "death", true), v -> KhoaConfig.put(ID, "death", v)),
			Setting.slider("death_max", "Death waypoints kept", "How many death points are kept; the oldest are deleted.", 1, 20, () -> KhoaConfig.getInt(ID, "death_max", 5), v -> KhoaConfig.put(ID, "death_max", v)),
			Setting.slider("timer_range", "Despawn timer range (blocks)", "Dropped items only age while their chunk is ticking, so the 5 minute countdown only runs while you are this close (horizontally).", 32, 320, () -> KhoaConfig.getInt(ID, "timer_range", 128), v -> KhoaConfig.put(ID, "timer_range", v)),
			KhoaKeybinds.setting(ID, "open_key", "Open manager key", "Opens the waypoint list.", () -> activate(Minecraft.getInstance())),
			KhoaKeybinds.setting(ID, "quick_key", "Quick waypoint key", "Creates a waypoint on the block you aim at (or stand on) with an automatic colour.", () -> quick(Minecraft.getInstance())));
	}

	static boolean show() {
		return KhoaConfig.getBool(ID, "visible", true);
	}

	static int range() {
		return KhoaConfig.getInt(ID, "timer_range", 128);
	}

	/** Opens the manager on the next tick (opening a screen straight from a command / click can fail). */
	@Override
	public void activate(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			KhoaFeature.say(mc, "Waypoints: join a world first.");
			return;
		}
		aimed = aimedBlock(mc);
		openNext = true;
	}

	@Override
	public void onInit() {
		ClientTickEvents.END_CLIENT_TICK.register(WaypointsFeature::tick);
		LevelRenderEvents.END_EXTRACTION.register(context -> drawBlocks());
		LevelRenderEvents.COLLECT_SUBMITS.register(WaypointsFeature::drawBeacons);
		HudElementRegistry.addLast(KhoaTools.id("waypoints_board"), WaypointHud::render);
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> WaypointStore.saveNow());
	}

	// ------------------------------------------------------------------ helpers shared with the screens

	/** The block under the crosshair, or null. */
	static BlockPos aimedBlock(Minecraft mc) {
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			return hit.getBlockPos();
		}
		return null;
	}

	/** The block you stand on. */
	static BlockPos standingBlock(Minecraft mc) {
		return BlockPos.containing(mc.player.getX(), mc.player.getY() - 0.2, mc.player.getZ());
	}

	/** Waypoint colour taken from the block's top texture (a bit brighter so it reads well). */
	static int autoColor(Minecraft mc, BlockPos pos) {
		BlockState state = mc.level.getBlockState(pos);
		if (state.isAir()) {
			state = mc.level.getBlockState(pos.below());
		}
		return KhoaBlockColor.brighten(KhoaBlockColor.averageRgb(state, Direction.UP), 0.15F);
	}

	static String dimension(Minecraft mc) {
		return mc.level.dimension().identifier().toString();
	}

	static boolean timerNear(Minecraft mc, Waypoint w) {
		if (mc.player == null) {
			return false;
		}
		int range = range();
		return Math.abs(mc.player.getX() - (w.x + 0.5)) <= range && Math.abs(mc.player.getZ() - (w.z + 0.5)) <= range;
	}

	private static void quick(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			return;
		}
		BlockPos pos = aimedBlock(mc);
		if (pos == null) {
			pos = standingBlock(mc);
		}
		Waypoint w = new Waypoint();
		w.name = "Waypoint " + (WaypointStore.current(mc).size() + 1);
		w.x = pos.getX();
		w.y = pos.getY();
		w.z = pos.getZ();
		w.dim = dimension(mc);
		w.rgb = autoColor(mc, pos);
		w.created = System.currentTimeMillis();
		w.modified = w.created;
		WaypointStore.add(mc, w);
		KhoaFeature.say(mc, "Waypoints: saved '" + w.name + "' at " + w.x + " " + w.y + " " + w.z);
	}

	/** Called by the mixin on a right click: swallowed while the area editor is open (nothing is placed or used). */
	public static boolean onUse(Minecraft mc) {
		return AreaEditSession.active();
	}

	/** Called by the mixin on attack / mine: swallowed while the area editor is open. */
	public static boolean onAttack() {
		return AreaEditSession.active();
	}

	// ------------------------------------------------------------------ tick: GUI request, deaths, despawn timers, Xaero

	private static void tick(Minecraft mc) {
		if (openNext) {
			openNext = false;
			if (mc.level != null && mc.player != null) {
				Screen current = mc.gui.screen();
				mc.gui.setScreen(new WaypointScreen(current instanceof ChatScreen ? null : current));
			}
		}
		if (mc.level == null || mc.player == null) {
			wasDead = false;
			return;
		}
		AreaEditSession.tick(mc);
		boolean dead = mc.player.isDeadOrDying();
		if (dead && !wasDead && KhoaConfig.getBool(ID, "death", true)) {
			recordDeath(mc);
		}
		wasDead = dead;

		String dim = dimension(mc);
		for (Waypoint w : WaypointStore.current(mc)) {
			if (w.death && w.ticksLeft > 0 && w.dim.equals(dim) && timerNear(mc, w)) {
				w.ticksLeft--;
				WaypointStore.markDirty();
			}
		}
		if (++saveTimer >= 200) {
			saveTimer = 0;
			WaypointStore.saveIfDirty();
			AreaRender.prune(WaypointStore.world(mc));
		}
		XaeroMapBridge.tick(mc);
	}

	private static void recordDeath(Minecraft mc) {
		BlockPos pos = standingBlock(mc);
		WorldData data = WaypointStore.world(mc);
		Waypoint w = new Waypoint();
		w.name = "Death";
		w.death = true;
		w.x = pos.getX();
		w.y = pos.getY();
		w.z = pos.getZ();
		w.dim = dimension(mc);
		w.rgb = WaypointBadge.DEATH_RGB;
		w.ticksLeft = Waypoint.DESPAWN_TICKS;
		w.created = System.currentTimeMillis();
		w.modified = w.created;
		data.waypoints.add(w);
		data.refresh();

		int keep = Math.max(1, KhoaConfig.getInt(ID, "death_max", 5));
		List<Waypoint> deaths = data.waypoints.stream().filter(x -> x.death && x.dim.equals(w.dim)).sorted((a, b) -> Long.compare(b.created, a.created)).toList();
		if (deaths.size() > keep) {
			WaypointStore.remove(mc, deaths.subList(keep, deaths.size()));
		} else {
			WaypointStore.saveNow();
		}
		KhoaFeature.say(mc, "Waypoints: death point saved at " + w.x + " " + w.y + " " + w.z + ". Your items despawn 5 minutes after you are near them.");
	}

	// ------------------------------------------------------------------ world: fill + outline on the waypoint's block / box (hidden behind walls)

	/**
	 * The real vanilla beacon beam (BeaconRenderer.submitBeaconBeam: the animated beam texture with solid core and glow) over every visible waypoint,
	 * from the bottom of the world up 2048 blocks like the top section of a normal beacon. The width grows with distance (distance / 96, the same
	 * rule as vanilla). Beyond the far limit the beam is drawn on the same line of sight but nearer, so it never drops out of the view distance.
	 */
	private static void drawBeacons(LevelRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || !show() || !KhoaConfig.getBool(ID, "beacon", false)) {
			return;
		}
		String dim = dimension(mc);
		WorldData data = WaypointStore.world(mc);
		var cam = mc.gameRenderer.mainCamera().position();
		double far = Math.max(64, mc.options.getEffectiveRenderDistance() * 16 * 0.85);
		double bottom = mc.level.getMinY();
		float time = Math.floorMod(mc.level.getGameTime(), 40L) + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		int maxDist = KhoaConfig.getInt(ID, "distance", 0);
		PoseStack pose = context.poseStack();
		for (Waypoint w : data.waypoints) {
			if (!w.dim.equals(dim) || !data.visible(w)) {
				continue;
			}
			var c = w.center();
			double dx = c.x - cam.x;
			double dz = c.z - cam.z;
			double horizontal = Math.hypot(dx, dz);
			if (horizontal < 1.0 || (maxDist > 0 && horizontal > maxDist)) {
				continue;
			}
			double ratio = horizontal > far ? far / horizontal : 1.0;
			double shown = horizontal * ratio;
			double cx = cam.x + dx * ratio;
			double cz = cam.z + dz * ratio;
			double yb = cam.y + (bottom - cam.y) * ratio;
			float radiusScale = Math.max(1F, (float) (shown / 96.0));
			pose.pushPose();
			pose.translate(cx - 0.5 - cam.x, yb - cam.y, cz - 0.5 - cam.z); // the beam is centred on the block corner + 0.5
			BeaconRenderer.submitBeaconBeam(pose, context.submitNodeCollector(), BeaconRenderer.BEAM_LOCATION, 1.0F, time, 0, 2048 + (int) (mc.level.getHeight()),
				0xFF000000 | w.rgb, 0.2F * radiusScale, 0.25F * radiusScale);
			pose.popPose();
		}
	}

	private static void drawBlocks() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || !show() || !KhoaConfig.getBool(ID, "overlay", true)) {
			return;
		}
		String dim = dimension(mc);
		WorldData data = WaypointStore.world(mc);
		int maxDist = KhoaConfig.getInt(ID, "distance", 0);
		double max = maxDist > 0 ? maxDist : 192.0; // the block overlay is only drawn nearby
		var eye = mc.player.getEyePosition();
		AreaEditSession.draw(eye);
		String editing = AreaEditSession.editingId();
		for (Waypoint w : data.waypoints) {
			if (!w.dim.equals(dim) || !data.visible(w) || w.id.equals(editing) || w.distanceTo(eye.x, eye.y, eye.z) > max) {
				continue;
			}
			if (w.isArea()) {
				AreaRender.draw(data, w, dim, eye);
				continue;
			}
			int stroke = 0xFF000000 | KhoaBlockColor.brighten(w.rgb, 0.2F);
			int fill = 0x55000000 | w.rgb;
			AABB box = new AABB(w.x, w.y, w.z, w.x + 1, w.y + 1, w.z + 1).inflate(0.003);
			Gizmos.cuboid(box, GizmoStyle.strokeAndFill(stroke, 3.0F, fill)); // no setAlwaysOnTop: walls hide it
		}
	}
}
