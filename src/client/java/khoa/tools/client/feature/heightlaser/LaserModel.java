package khoa.tools.client.feature.heightlaser;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix3d;

/**
 * Geometry of the laser device: a grey cube body with 6 round ports (12-sided cylinders); every port has a black lens, and an
 * enabled port of a switched-on laser has a glowing green lens. Quads are in world space, already shaded, and are used both for the
 * in-world drawing and for the software viewport of the config screen.
 */
public final class LaserModel {
	/** A flat 4-corner polygon (a triangle repeats its last corner). */
	public record Quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int argb) {
	}

	/** Ray hit on the device: distance from the ray start and the port (0..5, local direction index) that was hit. */
	public record Hit(double distance, int port) {
	}

	private static final double BODY = 0.21;
	private static final double PORT_BASE = 0.21;
	private static final double PORT_RADIUS = 0.14;
	private static final double LENS_RADIUS = 0.09;
	private static final int SIDES = 12;
	private static final Vec3 LIGHT = new Vec3(0.35, 0.85, 0.4).normalize();

	private static final int BODY_COLOR = 0x7C838C;
	private static final int PORT_COLOR = 0xA2A8B0;
	private static final int LENS_OFF = 0x0B0B0D;

	private LaserModel() {
	}

	public static List<Quad> quads(Laser laser) {
		Matrix3d m = laser.rotation();
		Vec3 c = laser.center();
		List<Quad> out = new ArrayList<>();
		boolean live = laser.on;

		// body cube: 6 faces
		for (int p = 0; p < 6; p++) {
			Vec3 n = Laser.axis(p);
			Vec3[] uv = perpendicular(n);
			Vec3 center = n.scale(BODY);
			out.add(shaded(m, c, center.add(uv[0].scale(BODY)).add(uv[1].scale(BODY)), center.add(uv[0].scale(-BODY)).add(uv[1].scale(BODY)),
				center.add(uv[0].scale(-BODY)).add(uv[1].scale(-BODY)), center.add(uv[0].scale(BODY)).add(uv[1].scale(-BODY)), BODY_COLOR, n));
		}

		for (int p = 0; p < 6; p++) {
			Vec3 n = Laser.axis(p);
			Vec3[] uv = perpendicular(n);
			double top = Laser.PORT_TIP - 0.01;
			boolean lit = live && laser.ports[p];
			Vec3[] ringBase = new Vec3[SIDES];
			Vec3[] ringTop = new Vec3[SIDES];
			Vec3[] lensRing = new Vec3[SIDES];
			Vec3[] glowRing = new Vec3[SIDES];
			for (int i = 0; i < SIDES; i++) {
				double a = 2 * Math.PI * i / SIDES;
				Vec3 radial = uv[0].scale(Math.cos(a)).add(uv[1].scale(Math.sin(a)));
				ringBase[i] = n.scale(PORT_BASE).add(radial.scale(PORT_RADIUS));
				ringTop[i] = n.scale(top).add(radial.scale(PORT_RADIUS));
				lensRing[i] = n.scale(top + 0.002).add(radial.scale(LENS_RADIUS));
				glowRing[i] = n.scale(top + 0.004).add(radial.scale(LENS_RADIUS * 0.55));
			}
			Vec3 lensCenter = n.scale(top + 0.002);
			Vec3 glowCenter = n.scale(top + 0.004);
			for (int i = 0; i < SIDES; i++) {
				int j = (i + 1) % SIDES;
				// side wall
				out.add(shaded(m, c, ringBase[i], ringBase[j], ringTop[j], ringTop[i], PORT_COLOR, ringBase[i].add(ringBase[j]).normalize()));
				// cap ring (annulus between the lens and the wall)
				out.add(shaded(m, c, ringTop[i], ringTop[j], lensRing[j], lensRing[i], PORT_COLOR, n));
				// lens
				out.add(shaded(m, c, lensCenter, lensRing[i], lensRing[j], lensRing[j], LENS_OFF, n));
				if (lit) {
					out.add(emissive(m, c, glowCenter, glowRing[i], glowRing[j], glowRing[j], 0xFF3CFF78));
				}
			}
		}
		return out;
	}

	private static Vec3[] perpendicular(Vec3 n) {
		Vec3 helper = Math.abs(n.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
		Vec3 u = n.cross(helper).normalize();
		Vec3 v = n.cross(u).normalize();
		return new Vec3[] { u, v };
	}

	private static Vec3 world(Matrix3d m, Vec3 center, Vec3 local) {
		return center.add(Laser.rotate(m, local));
	}

	private static Quad shaded(Matrix3d m, Vec3 c, Vec3 a, Vec3 b, Vec3 cc, Vec3 d, int rgb, Vec3 localNormal) {
		Vec3 n = Laser.rotate(m, localNormal).normalize();
		double lambert = Math.max(0, n.dot(LIGHT));
		double shade = 0.45 + 0.55 * lambert;
		int r = (int) Math.min(255, ((rgb >> 16) & 255) * shade);
		int g = (int) Math.min(255, ((rgb >> 8) & 255) * shade);
		int bl = (int) Math.min(255, (rgb & 255) * shade);
		return new Quad(world(m, c, a), world(m, c, b), world(m, c, cc), world(m, c, d), ARGB.color(255, r, g, bl));
	}

	private static Quad emissive(Matrix3d m, Vec3 c, Vec3 a, Vec3 b, Vec3 cc, Vec3 d, int argb) {
		return new Quad(world(m, c, a), world(m, c, b), world(m, c, cc), world(m, c, d), argb);
	}

	/**
	 * Ray against the device (body and 6 ports, boxes slightly bigger than the model so it is easy to click).
	 * @return nearest hit within {@code maxDistance}, or null
	 */
	public static Hit raycast(Laser laser, Vec3 from, Vec3 dir, double maxDistance) {
		Matrix3d inv = laser.rotation().transpose();
		Vec3 o = Laser.rotate(inv, from.subtract(laser.center()));
		Vec3 d = Laser.rotate(inv, dir);
		double best = Double.MAX_VALUE;
		int bestPort = -1;
		double pad = 0.025;
		// port boxes
		for (int p = 0; p < 6; p++) {
			Vec3 n = Laser.axis(p);
			double lo = PORT_BASE - 0.02;
			double hi = Laser.PORT_TIP + pad;
			double[] min = new double[3];
			double[] max = new double[3];
			for (int k = 0; k < 3; k++) {
				double axis = k == 0 ? n.x : k == 1 ? n.y : n.z;
				if (axis != 0) {
					min[k] = axis > 0 ? lo : -hi;
					max[k] = axis > 0 ? hi : -lo;
				} else {
					min[k] = -PORT_RADIUS - pad;
					max[k] = PORT_RADIUS + pad;
				}
			}
			double t = slab(o, d, min, max);
			if (t >= 0 && t < best) {
				best = t;
				bestPort = p;
			}
		}
		// body: report the port whose face the ray entered through
		double e = BODY + pad;
		double[] bmin = { -e, -e, -e };
		double[] bmax = { e, e, e };
		double tb = slab(o, d, bmin, bmax);
		if (tb >= 0 && tb < best) {
			Vec3 hit = o.add(d.scale(tb));
			best = tb;
			bestPort = dominantFace(hit);
		}
		if (bestPort < 0 || best > maxDistance) {
			return null;
		}
		return new Hit(best, bestPort);
	}

	private static int dominantFace(Vec3 p) {
		double ax = Math.abs(p.x);
		double ay = Math.abs(p.y);
		double az = Math.abs(p.z);
		if (ax >= ay && ax >= az) {
			return p.x > 0 ? 5 : 4; // EAST : WEST
		}
		if (ay >= az) {
			return p.y > 0 ? 1 : 0; // UP : DOWN
		}
		return p.z > 0 ? 3 : 2; // SOUTH : NORTH
	}

	/** Entry distance of the ray into the box, or -1 for a miss. */
	private static double slab(Vec3 o, Vec3 d, double[] min, double[] max) {
		double tMin = 0;
		double tMax = Double.MAX_VALUE;
		double[] oo = { o.x, o.y, o.z };
		double[] dd = { d.x, d.y, d.z };
		for (int k = 0; k < 3; k++) {
			if (Math.abs(dd[k]) < 1e-12) {
				if (oo[k] < min[k] || oo[k] > max[k]) {
					return -1;
				}
			} else {
				double t1 = (min[k] - oo[k]) / dd[k];
				double t2 = (max[k] - oo[k]) / dd[k];
				if (t1 > t2) {
					double t = t1;
					t1 = t2;
					t2 = t;
				}
				tMin = Math.max(tMin, t1);
				tMax = Math.min(tMax, t2);
				if (tMin > tMax) {
					return -1;
				}
			}
		}
		return tMin;
	}
}
