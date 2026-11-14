package khoa.tools.client.feature.waypoints;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import khoa.tools.KhoaTools;
import khoa.tools.client.config.KhoaConfig;

/**
 * Shows our waypoints on Xaero's minimap and world map through Xaero's own "third-party waypoints" list
 * (MinimapWorldContainer.getThirdPartyWaypointManager().get(originId)); both the minimap and the world map read that list.
 * Everything goes through reflection, so nothing breaks when Xaero is missing or changes: the bridge just turns itself off.
 * Only the waypoints of the dimension you are in are sent (Xaero shares one list between the dimensions of a world).
 */
final class XaeroMapBridge {
	private static final Identifier ORIGIN = KhoaTools.id("waypoints");

	private static boolean ready;
	private static boolean failed;
	private static Object minimapModule;
	private static Constructor<?> waypointCtor;
	private static Object[] colors = new Object[0];
	private static int[] colorHex = new int[0];
	private static Object purposeNormal;
	private static Object purposeDeath;
	private static Object purposeOldDeath;
	private static final Map<String, Method> METHODS = new HashMap<>();

	private static Object lastTarget;
	private static int lastChanges = -1;
	private static String lastDim;

	private XaeroMapBridge() {
	}

	static boolean available() {
		return !failed && FabricLoader.getInstance().isModLoaded("xaerominimap");
	}

	static void tick(Minecraft mc) {
		if (!available() || mc.level == null || mc.player == null) {
			return;
		}
		try {
			if (!ready) {
				init();
			}
			Object session = call(minimapModule, "getCurrentSession");
			if (session == null) {
				return;
			}
			Object world = call(call(session, "getWorldManager"), "getCurrentWorld");
			if (world == null) {
				return;
			}
			Object target = call(call(call(world, "getContainer"), "getThirdPartyWaypointManager"), "get", ORIGIN);
			if (target == null) {
				return;
			}
			if (!(boolean) call(target, "hasEnabledStateGetter")) {
				Supplier<Boolean> enabled = () -> KhoaConfig.getBool(WaypointsFeature.ID, "xaero_map", true);
				call(target, "setEnabledStateGetter", enabled);
			}
			String dim = WaypointsFeature.dimension(mc);
			if (target == lastTarget && lastChanges == WorldData.changes() && dim.equals(lastDim)) {
				return;
			}
			lastTarget = target;
			lastChanges = WorldData.changes();
			lastDim = dim;
			sync(mc, target, dim);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			failed = true;
			KhoaTools.LOGGER.warn("Xaero map integration for waypoints turned itself off: {}", e.toString());
		}
	}

	private static void sync(Minecraft mc, Object target, String dim) throws ReflectiveOperationException {
		call(target, "clear");
		WorldData data = WaypointStore.world(mc);
		for (Waypoint w : data.waypoints) {
			if (!w.dim.equals(dim) || !data.visible(w)) {
				continue;
			}
			Vec3 c = w.center();
			int x = w.isArea() ? (int) Math.floor(c.x) : w.x;
			int y = w.isArea() ? (int) Math.floor(c.y) : w.y;
			int z = w.isArea() ? (int) Math.floor(c.z) : w.z;
			String initial = w.initial();
			Object purpose = !w.death ? purposeNormal : w.deathRank == 0 ? purposeDeath : purposeOldDeath;
			Object waypoint = waypointCtor.newInstance(x, y, z, w.title(), initial == null ? "D" : initial, nearestColor(w.rgb), purpose);
			call(target, "add", "khoatools:" + w.id, waypoint);
		}
	}

	private static Object nearestColor(int rgb) {
		int best = 0;
		long bestDistance = Long.MAX_VALUE;
		for (int i = 0; i < colorHex.length; i++) {
			long dr = ((rgb >> 16) & 0xFF) - ((colorHex[i] >> 16) & 0xFF);
			long dg = ((rgb >> 8) & 0xFF) - ((colorHex[i] >> 8) & 0xFF);
			long db = (rgb & 0xFF) - (colorHex[i] & 0xFF);
			long d = dr * dr + dg * dg + db * db;
			if (d < bestDistance) {
				bestDistance = d;
				best = i;
			}
		}
		return colors[best];
	}

	private static void init() throws ReflectiveOperationException {
		minimapModule = Class.forName("xaero.hud.minimap.BuiltInHudModules").getField("MINIMAP").get(null);
		Class<?> colorClass = Class.forName("xaero.hud.minimap.waypoint.WaypointColor");
		Class<?> purposeClass = Class.forName("xaero.hud.minimap.waypoint.WaypointPurpose");
		Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
		colors = colorClass.getEnumConstants();
		colorHex = new int[colors.length];
		for (int i = 0; i < colors.length; i++) {
			colorHex[i] = (int) call(colors[i], "getHex");
		}
		Object[] purposes = purposeClass.getEnumConstants();
		for (Object p : purposes) {
			switch (((Enum<?>) p).name()) {
				case "NORMAL" -> purposeNormal = p;
				case "DEATH" -> purposeDeath = p;
				case "OLD_DEATH" -> purposeOldDeath = p;
				default -> { }
			}
		}
		waypointCtor = waypointClass.getConstructor(int.class, int.class, int.class, String.class, String.class, colorClass, purposeClass);
		ready = true;
	}

	/** Calls a public no-overload-ambiguity method by name and argument count; the arguments may be boxed primitives. */
	private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
		if (target == null) {
			return null;
		}
		String key = target.getClass().getName() + "#" + name + "/" + args.length;
		Method method = METHODS.get(key);
		if (method == null) {
			for (Method m : target.getClass().getMethods()) {
				if (m.getName().equals(name) && m.getParameterCount() == args.length) {
					method = m;
					break;
				}
			}
			if (method == null) {
				throw new NoSuchMethodException(key);
			}
			METHODS.put(key, method);
		}
		return method.invoke(target, args);
	}
}
