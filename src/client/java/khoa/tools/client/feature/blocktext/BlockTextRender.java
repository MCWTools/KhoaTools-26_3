package khoa.tools.client.feature.blocktext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;

/** Draws the texts as flat coloured rectangles lying just above the face of their block (depth tested: walls hide them). */
final class BlockTextRender {
	private static final double FILL_LIFT = 0.012;
	private static final double OUTLINE_LIFT = 0.006;
	/** Part of the face the text may use. */
	private static final double USABLE = 0.9;
	private static final double MAX_DISTANCE = 32;

	private BlockTextRender() {
	}

	/** Right (s) and up (t) direction when you look at the face from outside, so that s x t points out of the block. */
	static Vec3[] axes(Direction d) {
		return switch (d) {
			case SOUTH -> new Vec3[] { new Vec3(1, 0, 0), new Vec3(0, 1, 0) };
			case NORTH -> new Vec3[] { new Vec3(-1, 0, 0), new Vec3(0, 1, 0) };
			case EAST -> new Vec3[] { new Vec3(0, 0, -1), new Vec3(0, 1, 0) };
			case WEST -> new Vec3[] { new Vec3(0, 0, 1), new Vec3(0, 1, 0) };
			case UP -> new Vec3[] { new Vec3(1, 0, 0), new Vec3(0, 0, -1) };
			case DOWN -> new Vec3[] { new Vec3(1, 0, 0), new Vec3(0, 0, 1) };
		};
	}

	static void draw(Minecraft mc, BlockText t, Vec3 eye) {
		Direction face = Direction.byName(t.face);
		if (face == null || t.text.isBlank()) {
			return;
		}
		Vec3 normal = Vec3.atLowerCornerOf(face.getUnitVec3i());
		Vec3 centre = new Vec3(t.x + 0.5, t.y + 0.5, t.z + 0.5).add(normal.scale(0.5));
		Vec3 toEye = eye.subtract(centre);
		if (toEye.dot(normal) <= 0 || toEye.lengthSqr() > MAX_DISTANCE * MAX_DISTANCE) {
			return; // behind the face or too far
		}
		String key = BlockTextLayout.key(t);
		if (t.layout == null || !key.equals(t.layoutKey)) {
			t.layout = BlockTextLayout.build(t);
			t.layoutKey = key;
		}
		BlockTextLayout layout = t.layout;
		double unit = USABLE / layout.cols;
		Vec3[] st = axes(face);
		if (t.outline) {
			emit(layout.outline, layout.cols, unit, centre.add(normal.scale(OUTLINE_LIFT)), st, 0xFF000000 | t.outlineRgb);
		}
		emit(layout.fill, layout.cols, unit, centre.add(normal.scale(FILL_LIFT)), st, 0xFF000000 | t.rgb);
	}

	private static void emit(Iterable<int[]> rects, int cols, double unit, Vec3 centre, Vec3[] st, int argb) {
		double half = cols * unit / 2;
		for (int[] r : rects) {
			double s0 = r[0] * unit - half;
			double s1 = r[2] * unit - half;
			double t1 = half - r[1] * unit; // pixel rows go down, t goes up
			double t0 = half - r[3] * unit;
			Gizmos.rect(point(centre, st, s0, t0), point(centre, st, s1, t0), point(centre, st, s1, t1), point(centre, st, s0, t1), GizmoStyle.fill(argb));
		}
	}

	private static Vec3 point(Vec3 centre, Vec3[] st, double s, double t) {
		return centre.add(st[0].scale(s)).add(st[1].scale(t));
	}
}
