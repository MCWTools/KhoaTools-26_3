package khoa.tools.client.feature.waypoints;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import khoa.api.color.KhoaBlockColor;

/**
 * Draws an area waypoint as one shape: translucent fill on the outside faces and only the outer edges of the whole union of its regions
 * (no lines for every block, none between touching or overlapping regions). Blocks that carry a visible single waypoint are cut out.
 * Faces are lifted 0.004 and edges pushed 0.01 away from the shape so the lines are not hidden by the neighbouring blocks or fight with the fill.
 * The geometry (see {@link RegionGeometry}) is cached until the waypoints change.
 */
final class AreaRender {
	private static final double FACE_LIFT = 0.004;
	private static final double EDGE_PUSH = 0.01;
	private static final float EDGE_WIDTH = 3.0F;

	private record Built(int changes, RegionGeometry.Result geometry, int[] bounds) {
	}

	private static final Map<String, Built> CACHE = new HashMap<>();

	private AreaRender() {
	}

	/** A saved area waypoint, hidden by walls. */
	static void draw(WorldData data, Waypoint w, String dim, Vec3 eye) {
		Built built = CACHE.get(w.id);
		if (built == null || built.changes != WorldData.changes()) {
			List<int[]> holes = new ArrayList<>();
			for (Waypoint other : data.waypoints) {
				if (!other.isArea() && other.dim.equals(dim) && data.visible(other) && w.contains(other.x, other.y, other.z)) {
					holes.add(new int[] { other.x, other.y, other.z });
				}
			}
			built = new Built(WorldData.changes(), RegionGeometry.build(w.regions(), holes), w.bounds());
			CACHE.put(w.id, built);
		}
		render(built.geometry, built.bounds, w.rgb, eye, false, 0x38);
	}

	/** Used by the area editor: raw shapes, always on top so no edge is ever missing. */
	static void drawEditing(RegionGeometry.Result geometry, int[] bounds, int rgb, Vec3 eye, int fillAlpha) {
		render(geometry, bounds, rgb, eye, true, fillAlpha);
	}

	/** Drops cached shapes of areas that no longer exist. */
	static void prune(WorldData data) {
		Set<String> alive = new HashSet<>();
		for (Waypoint w : data.waypoints) {
			alive.add(w.id);
		}
		CACHE.keySet().retainAll(alive);
	}

	private static void render(RegionGeometry.Result g, int[] bounds, int rgb, Vec3 eye, boolean onTop, int fillAlpha) {
		int fill = (fillAlpha << 24) | rgb;
		int stroke = 0xFF000000 | KhoaBlockColor.brighten(rgb, onTop ? 0.35F : 0.2F);
		if (g == null) { // shape too complicated for the grid: just its bounding box
			AABB box = new AABB(bounds[0], bounds[1], bounds[2], bounds[3] + 1, bounds[4] + 1, bounds[5] + 1).inflate(0.01);
			var props = Gizmos.cuboid(box, GizmoStyle.strokeAndFill(stroke, EDGE_WIDTH, fill));
			if (onTop) {
				props.setAlwaysOnTop();
			}
			return;
		}
		double[] e = { eye.x, eye.y, eye.z };
		for (RegionGeometry.Rect r : g.rects()) {
			double plane = r.plane() + (r.positive() ? FACE_LIFT : -FACE_LIFT);
			if ((e[r.axis()] - plane) * (r.positive() ? 1 : -1) <= 0) {
				continue; // faces the other way
			}
			int u = RegionGeometry.uAxis(r.axis());
			int v = RegionGeometry.vAxis(r.axis());
			Vec3 p0 = point(r.axis(), plane, u, r.u0(), v, r.v0());
			Vec3 pu = point(r.axis(), plane, u, r.u1(), v, r.v0());
			Vec3 puv = point(r.axis(), plane, u, r.u1(), v, r.v1());
			Vec3 pv = point(r.axis(), plane, u, r.u0(), v, r.v1());
			var props = r.positive() ? Gizmos.rect(p0, pu, puv, pv, GizmoStyle.fill(fill)) : Gizmos.rect(p0, pv, puv, pu, GizmoStyle.fill(fill));
			if (onTop) {
				props.setAlwaysOnTop();
			}
		}
		for (RegionGeometry.Edge ed : g.edges()) {
			int o1 = RegionGeometry.firstOther(ed.axis());
			int o2 = RegionGeometry.secondOther(ed.axis());
			double a = ed.a() + ed.sa() * EDGE_PUSH;
			double b = ed.b() + ed.sb() * EDGE_PUSH;
			var props = Gizmos.line(point(ed.axis(), ed.t0(), o1, a, o2, b), point(ed.axis(), ed.t1(), o1, a, o2, b), stroke, EDGE_WIDTH);
			if (onTop) {
				props.setAlwaysOnTop();
			}
		}
	}

	private static Vec3 point(int a, double aValue, int u, double uValue, int v, double vValue) {
		double[] p = new double[3];
		p[a] = aValue;
		p[u] = uValue;
		p[v] = vValue;
		return new Vec3(p[0], p[1], p[2]);
	}
}
