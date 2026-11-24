package khoa.tools.client.feature.heightlaser;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import khoa.api.color.KhoaBlockColor;

/**
 * Tiny software 3D renderer for the laser config screen (the GUI cannot draw the world twice). It draws the solid blocks within
 * {@link #WALL_RADIUS} blocks of the laser (only their visible faces, flat shaded with the block's texture colour), the device and its
 * beams into a {@link DynamicTexture}. The screen calls {@link #render} about 10 times a second.
 */
final class LaserViewport {
	static final int WALL_RADIUS = 10;
	private static final int MAX_WALL_QUADS = 14000;
	private static final double NEAR = 0.05;
	private static final double VIEW_BEAM = 64;
	private static final long WALL_REFRESH_MS = 700;

	final int w;
	final int h;
	final DynamicTexture texture;
	private final float[] invZ;
	private final int[] pixels;

	// camera
	Vec3 eye = new Vec3(0, 0, 0);
	Vec3 target = new Vec3(0, 0, 1);
	private Vec3 right = new Vec3(1, 0, 0);
	private Vec3 up = new Vec3(0, 1, 0);
	private Vec3 fwd = new Vec3(0, 0, 1);
	private final double focal;

	private List<LaserModel.Quad> walls = new ArrayList<>();
	private BlockPos wallsFor;
	private long wallsAt;

	LaserViewport(int w, int h, String name) {
		this.w = w;
		this.h = h;
		this.texture = new DynamicTexture(() -> name, w, h, false);
		this.invZ = new float[w * h];
		this.pixels = new int[w * h];
		this.focal = (h / 2.0) / Math.tan(Math.toRadians(35));
	}

	void close() {
		texture.close();
	}

	void setCamera(Vec3 eye, Vec3 target) {
		this.eye = eye;
		this.target = target;
		Vec3 f = target.subtract(eye);
		fwd = f.lengthSqr() < 1e-9 ? new Vec3(0, 0, 1) : f.normalize();
		Vec3 r = fwd.cross(new Vec3(0, 1, 0));
		right = r.lengthSqr() < 1e-9 ? new Vec3(1, 0, 0) : r.normalize();
		up = right.cross(fwd).normalize();
	}

	// ------------------------------------------------------------------ scene

	void render(ClientLevel level, Laser laser) {
		// background
		for (int y = 0; y < h; y++) {
			float t = y / (float) h;
			int c = ARGB.color(255, (int) (14 + 22 * t), (int) (18 + 26 * t), (int) (30 + 34 * t));
			for (int x = 0; x < w; x++) {
				pixels[y * w + x] = c;
			}
		}
		java.util.Arrays.fill(invZ, 0f);

		long now = System.currentTimeMillis();
		if (wallsFor == null || !wallsFor.equals(laser.cell) || now - wallsAt > WALL_REFRESH_MS) {
			walls = collectWalls(level, laser.cell);
			wallsFor = laser.cell;
			wallsAt = now;
		}
		for (LaserModel.Quad q : walls) {
			fillQuad(q, true);
		}
		for (LaserModel.Quad q : LaserModel.quads(laser)) {
			fillQuad(q, true);
		}
		for (int p = 0; p < 6; p++) {
			if (!laser.on || !laser.ports[p]) {
				continue;
			}
			LaserBeams.Beam b = LaserBeams.compute(laser, p);
			Vec3 end = b.end();
			if (end.distanceTo(b.start()) > VIEW_BEAM) {
				end = b.start().add(end.subtract(b.start()).normalize().scale(VIEW_BEAM));
			}
			line(b.start(), end, ARGB.color(60, 0, 255, 90), 4.5);
			line(b.start(), end, ARGB.color(140, 60, 255, 120), 2.2);
			line(b.start(), end, ARGB.color(255, 215, 255, 225), 0.9);
			if (b.blocked() && end == b.end()) {
				line(end, end, ARGB.color(220, 90, 255, 140), 3.5);
			}
		}

		NativeImage img = texture.getPixels();
		if (img != null) {
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					img.setPixel(x, y, pixels[y * w + x]);
				}
			}
			texture.upload();
		}
	}

	private List<LaserModel.Quad> collectWalls(ClientLevel level, BlockPos center) {
		List<LaserModel.Quad> out = new ArrayList<>();
		int r = WALL_RADIUS;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos nb = new BlockPos.MutableBlockPos();
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					if (dx * dx + dy * dy + dz * dz > r * r + r) {
						continue;
					}
					pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
						continue;
					}
					BlockState s = level.getBlockState(pos);
					if (s.isAir() || !s.canOcclude()) {
						continue;
					}
					int rgb = KhoaBlockColor.averageRgb(s);
					for (Direction d : Direction.values()) {
						nb.setWithOffset(pos, d);
						if (level.getBlockState(nb).canOcclude()) {
							continue;
						}
						out.add(face(pos, d, rgb));
						if (out.size() >= MAX_WALL_QUADS) {
							return out;
						}
					}
				}
			}
		}
		return out;
	}

	private static LaserModel.Quad face(BlockPos p, Direction d, int rgb) {
		double x = p.getX();
		double y = p.getY();
		double z = p.getZ();
		Vec3 a;
		Vec3 b;
		Vec3 c;
		Vec3 e;
		switch (d) {
			case UP -> {
				a = new Vec3(x, y + 1, z);
				b = new Vec3(x + 1, y + 1, z);
				c = new Vec3(x + 1, y + 1, z + 1);
				e = new Vec3(x, y + 1, z + 1);
			}
			case DOWN -> {
				a = new Vec3(x, y, z);
				b = new Vec3(x, y, z + 1);
				c = new Vec3(x + 1, y, z + 1);
				e = new Vec3(x + 1, y, z);
			}
			case NORTH -> {
				a = new Vec3(x, y, z);
				b = new Vec3(x + 1, y, z);
				c = new Vec3(x + 1, y + 1, z);
				e = new Vec3(x, y + 1, z);
			}
			case SOUTH -> {
				a = new Vec3(x, y, z + 1);
				b = new Vec3(x, y + 1, z + 1);
				c = new Vec3(x + 1, y + 1, z + 1);
				e = new Vec3(x + 1, y, z + 1);
			}
			case WEST -> {
				a = new Vec3(x, y, z);
				b = new Vec3(x, y + 1, z);
				c = new Vec3(x, y + 1, z + 1);
				e = new Vec3(x, y, z + 1);
			}
			default -> {
				a = new Vec3(x + 1, y, z);
				b = new Vec3(x + 1, y, z + 1);
				c = new Vec3(x + 1, y + 1, z + 1);
				e = new Vec3(x + 1, y + 1, z);
			}
		}
		double shade = switch (d) {
			case UP -> 1.0;
			case DOWN -> 0.5;
			case NORTH, SOUTH -> 0.8;
			default -> 0.65;
		};
		int r = (int) (((rgb >> 16) & 255) * shade);
		int g = (int) (((rgb >> 8) & 255) * shade);
		int bl = (int) ((rgb & 255) * shade);
		return new LaserModel.Quad(a, b, c, e, ARGB.color(255, r, g, bl));
	}

	// ------------------------------------------------------------------ rasteriser

	/** Camera space: x right, y up, z forward. */
	private double[] toCam(Vec3 p) {
		Vec3 r = p.subtract(eye);
		return new double[] { r.dot(right), r.dot(up), r.dot(fwd) };
	}

	private void fillQuad(LaserModel.Quad q, boolean depthWrite) {
		double[][] v = { toCam(q.a()), toCam(q.b()), toCam(q.c()), toCam(q.d()) };
		// near clip of the polygon
		List<double[]> poly = new ArrayList<>(5);
		for (int i = 0; i < 4; i++) {
			double[] cur = v[i];
			double[] nxt = v[(i + 1) % 4];
			boolean curIn = cur[2] >= NEAR;
			boolean nxtIn = nxt[2] >= NEAR;
			if (curIn) {
				poly.add(cur);
			}
			if (curIn != nxtIn) {
				double t = (NEAR - cur[2]) / (nxt[2] - cur[2]);
				poly.add(new double[] { cur[0] + (nxt[0] - cur[0]) * t, cur[1] + (nxt[1] - cur[1]) * t, NEAR });
			}
		}
		if (poly.size() < 3) {
			return;
		}
		double[][] s = new double[poly.size()][3];
		for (int i = 0; i < s.length; i++) {
			double[] c = poly.get(i);
			s[i][0] = w / 2.0 + c[0] / c[2] * focal;
			s[i][1] = h / 2.0 - c[1] / c[2] * focal;
			s[i][2] = 1.0 / c[2];
		}
		for (int i = 1; i + 1 < s.length; i++) {
			triangle(s[0], s[i], s[i + 1], q.argb(), depthWrite);
		}
	}

	private void triangle(double[] a, double[] b, double[] c, int color, boolean depthWrite) {
		int minX = (int) Math.max(0, Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
		int maxX = (int) Math.min(w - 1, Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
		int minY = (int) Math.max(0, Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
		int maxY = (int) Math.min(h - 1, Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
		double area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
		if (Math.abs(area) < 1e-9) {
			return;
		}
		double inv = 1.0 / area;
		for (int y = minY; y <= maxY; y++) {
			double py = y + 0.5;
			for (int x = minX; x <= maxX; x++) {
				double px = x + 0.5;
				double w0 = ((b[0] - px) * (c[1] - py) - (b[1] - py) * (c[0] - px)) * inv;
				double w1 = ((c[0] - px) * (a[1] - py) - (c[1] - py) * (a[0] - px)) * inv;
				double w2 = 1 - w0 - w1;
				if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6) {
					continue;
				}
				float z = (float) (w0 * a[2] + w1 * b[2] + w2 * c[2]);
				int i = y * w + x;
				if (z > invZ[i]) {
					pixels[i] = blend(pixels[i], color);
					if (depthWrite) {
						invZ[i] = z;
					}
				}
			}
		}
	}

	/** A round-capped line with the given radius in pixels, depth tested against the scene, alpha blended. */
	private void line(Vec3 p0, Vec3 p1, int argb, double radius) {
		double[] a = toCam(p0);
		double[] b = toCam(p1);
		if (a[2] < NEAR && b[2] < NEAR) {
			return;
		}
		if (a[2] < NEAR) {
			a = clipNear(a, b);
		} else if (b[2] < NEAR) {
			b = clipNear(b, a);
		}
		double ax = w / 2.0 + a[0] / a[2] * focal;
		double ay = h / 2.0 - a[1] / a[2] * focal;
		double bx = w / 2.0 + b[0] / b[2] * focal;
		double by = h / 2.0 - b[1] / b[2] * focal;
		double az = 1 / a[2];
		double bz = 1 / b[2];
		double len = Math.hypot(bx - ax, by - ay);
		int steps = (int) Math.min(4000, Math.max(1, Math.ceil(len * 1.5)));
		int r = (int) Math.ceil(radius);
		for (int s = 0; s <= steps; s++) {
			double t = s / (double) steps;
			double cx = ax + (bx - ax) * t;
			double cy = ay + (by - ay) * t;
			float z = (float) (az + (bz - az) * t);
			for (int dy = -r; dy <= r; dy++) {
				for (int dx = -r; dx <= r; dx++) {
					if (dx * dx + dy * dy > radius * radius + 0.25) {
						continue;
					}
					int x = (int) Math.floor(cx) + dx;
					int y = (int) Math.floor(cy) + dy;
					if (x < 0 || y < 0 || x >= w || y >= h) {
						continue;
					}
					int i = y * w + x;
					if (z * 1.0005f >= invZ[i]) {
						pixels[i] = blend(pixels[i], argb);
					}
				}
			}
		}
	}

	private static double[] clipNear(double[] behind, double[] front) {
		double t = (NEAR - behind[2]) / (front[2] - behind[2]);
		return new double[] { behind[0] + (front[0] - behind[0]) * t, behind[1] + (front[1] - behind[1]) * t, NEAR };
	}

	private static int blend(int dst, int src) {
		int a = ARGB.alpha(src);
		if (a >= 255) {
			return src | 0xFF000000;
		}
		int r = (ARGB.red(src) * a + ARGB.red(dst) * (255 - a)) / 255;
		int g = (ARGB.green(src) * a + ARGB.green(dst) * (255 - a)) / 255;
		int b = (ARGB.blue(src) * a + ARGB.blue(dst) * (255 - a)) / 255;
		return ARGB.color(255, r, g, b);
	}
}
