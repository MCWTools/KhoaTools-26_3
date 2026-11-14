package khoa.tools.client.feature.illegal.spawnerfinder;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;

import khoa.tools.client.feature.illegal.FinderMode;

/**
 * Through-wall tile on every face of a spawner block that faces you: dark grey with a mob coloured square (75 %) in the middle;
 * trial spawners get a copper top half. Drawn with vanilla gizmos (always on top), so it shows through terrain.
 */
final class SpawnerOverlay {
	private static final double LIFT_BG = 0.004;
	private static final double LIFT_CENTER = 0.008;
	private static final GizmoStyle GRAY = GizmoStyle.fill(0x30000000 | (SpawnerLook.DARK_GRAY & 0xFFFFFF));
	private static final GizmoStyle COPPER = GizmoStyle.fill(0x3C000000 | (SpawnerLook.COPPER & 0xFFFFFF));
	private static final int OUTLINE_NORMAL = 0xFFB4B4B4;
	private static final int OUTLINE_TRIAL = 0xFFFFA45E;
	private static final double CENTER_LO = (1.0 - SpawnerLook.CENTER_SCALE) / 2.0;
	private static final double CENTER_HI = 1.0 - CENTER_LO;

	private SpawnerOverlay() {
	}

	static void register() {
		LevelRenderEvents.END_EXTRACTION.register(context -> draw());
	}

	private static void draw() {
		Minecraft mc = Minecraft.getInstance();
		if (!SpawnerFinderFeature.isOn() || mc.level == null || mc.player == null) {
			return;
		}
		SpawnerScanner.scanIfNeeded(mc);
		if (SpawnerFinderFeature.pathfinding()) {
			SpawnerFinderFeature.PATH.render(mc);
		}
		Vec3 eye = mc.player.getEyePosition();
		int mode = FinderMode.get("spawnerfinder");
		Vec3 lineFrom = FinderMode.lineStart(mc);
		double maxDist = SpawnerFinderFeature.maxDistance();
		double maxSq = maxDist > 0 ? maxDist * maxDist : Double.MAX_VALUE;
		int maxCount = SpawnerFinderFeature.maxCount() > 0 ? SpawnerFinderFeature.maxCount() : Integer.MAX_VALUE;
		int drawn = 0;
		for (SpawnerScanner.Info s : SpawnerFinderFeature.filtered()) {
			if (s.distance() * s.distance() > maxSq) {
				continue;
			}
			if (drawn++ >= maxCount) {
				break;
			}
			int center = s.type() == null ? 0xFFFF00FF : SpawnerMobs.color(s.type());
			GizmoStyle centerStyle = GizmoStyle.strokeAndFill(0xFF000000 | (center & 0xFFFFFF), 1.5F, 0x55000000 | (center & 0xFFFFFF));
			if (FinderMode.lines(mode)) {
				FinderMode.line(lineFrom, Vec3.atCenterOf(s.pos()), 0xFF000000 | center);
			}
			if (!FinderMode.box(mode)) {
				continue;
			}
			// opaque outline of the whole block (see-through tiles, solid edges)
			Gizmos.cuboid(s.pos(), GizmoStyle.stroke(s.trial() ? OUTLINE_TRIAL : OUTLINE_NORMAL, 2.0F)).setAlwaysOnTop();
			for (Direction face : Direction.values()) {
				double fx = s.pos().getX() + 0.5 + face.getStepX() * 0.5;
				double fy = s.pos().getY() + 0.5 + face.getStepY() * 0.5;
				double fz = s.pos().getZ() + 0.5 + face.getStepZ() * 0.5;
				double toEye = (eye.x - fx) * face.getStepX() + (eye.y - fy) * face.getStepY() + (eye.z - fz) * face.getStepZ();
				if (toEye <= 0) {
					continue; // back face: only the faces turned towards you are drawn
				}
				drawFace(s, face, centerStyle);
			}
		}
	}

	private static void drawFace(SpawnerScanner.Info s, Direction face, GizmoStyle centerStyle) {
		// (u, v) are the face's two axes, ordered so that corners c, c+u, c+u+v, c+v run counter-clockwise seen from outside.
		Direction.Axis a = face.getAxis();
		Vec3 u = unit(a == Direction.Axis.X ? Direction.Axis.Y : a == Direction.Axis.Y ? Direction.Axis.Z : Direction.Axis.X);
		Vec3 v = unit(a == Direction.Axis.X ? Direction.Axis.Z : a == Direction.Axis.Y ? Direction.Axis.X : Direction.Axis.Y);
		if (face.getAxisDirection() == Direction.AxisDirection.NEGATIVE) {
			Vec3 t = u;
			u = v;
			v = t;
		}
		boolean uIsUp = u.y != 0;
		boolean vIsUp = v.y != 0;
		double ox = s.pos().getX();
		double oy = s.pos().getY();
		double oz = s.pos().getZ();
		if (!s.trial()) {
			rect(ox, oy, oz, face, u, v, 0, 1, 0, 1, LIFT_BG, GRAY);
		} else if (uIsUp) { // side face: copper top half
			rect(ox, oy, oz, face, u, v, 0.5, 1, 0, 1, LIFT_BG, COPPER);
			rect(ox, oy, oz, face, u, v, 0, 0.5, 0, 1, LIFT_BG, GRAY);
		} else if (vIsUp) {
			rect(ox, oy, oz, face, u, v, 0, 1, 0.5, 1, LIFT_BG, COPPER);
			rect(ox, oy, oz, face, u, v, 0, 1, 0, 0.5, LIFT_BG, GRAY);
		} else if (u.z != 0) { // top / bottom face: copper on the north half
			rect(ox, oy, oz, face, u, v, 0, 0.5, 0, 1, LIFT_BG, COPPER);
			rect(ox, oy, oz, face, u, v, 0.5, 1, 0, 1, LIFT_BG, GRAY);
		} else {
			rect(ox, oy, oz, face, u, v, 0, 1, 0, 0.5, LIFT_BG, COPPER);
			rect(ox, oy, oz, face, u, v, 0, 1, 0.5, 1, LIFT_BG, GRAY);
		}
		rect(ox, oy, oz, face, u, v, CENTER_LO, CENTER_HI, CENTER_LO, CENTER_HI, LIFT_CENTER, centerStyle);
	}

	private static Vec3 unit(Direction.Axis axis) {
		return switch (axis) {
			case X -> new Vec3(1, 0, 0);
			case Y -> new Vec3(0, 1, 0);
			case Z -> new Vec3(0, 0, 1);
		};
	}

	/** Face rectangle [u0,u1] x [v0,v1] (fractions of the block face), lifted off the surface so it does not z-fight. */
	private static void rect(double ox, double oy, double oz, Direction face, Vec3 u, Vec3 v, double u0, double u1, double v0, double v1,
		double lift, GizmoStyle style) {
		// the face plane touches the +1 side of its axis for a positive face, the 0 side for a negative face
		double bx = ox + (face.getStepX() > 0 ? 1 : 0) + face.getStepX() * lift;
		double by = oy + (face.getStepY() > 0 ? 1 : 0) + face.getStepY() * lift;
		double bz = oz + (face.getStepZ() > 0 ? 1 : 0) + face.getStepZ() * lift;
		Vec3 p0 = new Vec3(bx + u.x * u0 + v.x * v0, by + u.y * u0 + v.y * v0, bz + u.z * u0 + v.z * v0);
		Vec3 p1 = new Vec3(bx + u.x * u1 + v.x * v0, by + u.y * u1 + v.y * v0, bz + u.z * u1 + v.z * v0);
		Vec3 p2 = new Vec3(bx + u.x * u1 + v.x * v1, by + u.y * u1 + v.y * v1, bz + u.z * u1 + v.z * v1);
		Vec3 p3 = new Vec3(bx + u.x * u0 + v.x * v1, by + u.y * u0 + v.y * v1, bz + u.z * u0 + v.z * v1);
		Gizmos.rect(p0, p1, p2, p3, style).setAlwaysOnTop();
	}
}
