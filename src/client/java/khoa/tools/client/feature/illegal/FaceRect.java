package khoa.tools.client.feature.illegal;

import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;

/** Draws one block face as a see-through gizmo rectangle (always on top), wound counter-clockwise as seen from outside the block. */
public final class FaceRect {
	private static final double LIFT = 0.004;

	private FaceRect() {
	}

	/** True when the face of the block at (x, y, z) is turned towards {@code eye}. */
	public static boolean facesEye(int x, int y, int z, Direction face, Vec3 eye) {
		double fx = x + 0.5 + face.getStepX() * 0.5;
		double fy = y + 0.5 + face.getStepY() * 0.5;
		double fz = z + 0.5 + face.getStepZ() * 0.5;
		return (eye.x - fx) * face.getStepX() + (eye.y - fy) * face.getStepY() + (eye.z - fz) * face.getStepZ() > 0;
	}

	public static void draw(int x, int y, int z, Direction face, GizmoStyle style) {
		Direction.Axis a = face.getAxis();
		double[] u = axis(a == Direction.Axis.X ? 1 : a == Direction.Axis.Y ? 2 : 0);
		double[] v = axis(a == Direction.Axis.X ? 2 : a == Direction.Axis.Y ? 0 : 1);
		if (face.getAxisDirection() == Direction.AxisDirection.NEGATIVE) {
			double[] t = u;
			u = v;
			v = t;
		}
		double bx = x + (face.getStepX() > 0 ? 1 : 0) + face.getStepX() * LIFT;
		double by = y + (face.getStepY() > 0 ? 1 : 0) + face.getStepY() * LIFT;
		double bz = z + (face.getStepZ() > 0 ? 1 : 0) + face.getStepZ() * LIFT;
		Vec3 p0 = new Vec3(bx, by, bz);
		Vec3 p1 = new Vec3(bx + u[0], by + u[1], bz + u[2]);
		Vec3 p2 = new Vec3(bx + u[0] + v[0], by + u[1] + v[1], bz + u[2] + v[2]);
		Vec3 p3 = new Vec3(bx + v[0], by + v[1], bz + v[2]);
		Gizmos.rect(p0, p1, p2, p3, style).setAlwaysOnTop();
	}

	private static double[] axis(int i) {
		double[] r = new double[3];
		r[i] = 1;
		return r;
	}
}
