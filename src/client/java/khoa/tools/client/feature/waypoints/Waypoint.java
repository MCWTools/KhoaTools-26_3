package khoa.tools.client.feature.waypoints;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** One saved waypoint: a single block, or an area (a box between two blocks). Public fields because it is stored with Gson (Waypoints.json). */
public final class Waypoint {
	/** Ticks a dropped item lives (5 minutes). */
	public static final int DESPAWN_TICKS = 6000;

	public String id = UUID.randomUUID().toString();
	public String name = "Waypoint";
	/** Position of the block. For an area waypoint this is only an anchor (a corner of the first region). */
	public int x;
	public int y;
	public int z;
	/** Area waypoint: single blocks, each {x, y, z}. Empty on a normal single waypoint. */
	public List<int[]> blocks = new ArrayList<>();
	/** Area waypoint: boxes, each {minX, minY, minZ, maxX, maxY, maxZ} (block coordinates, both ends included). */
	public List<int[]> boxes = new ArrayList<>();
	/** Legacy (first version of areas: one box between two corners). Read once, converted to {@link #boxes}, then null. */
	public Boolean area;
	public Integer x2;
	public Integer y2;
	public Integer z2;
	/** Dimension id, e.g. minecraft:overworld. */
	public String dim = "minecraft:overworld";
	/** 0xRRGGBB. */
	public int rgb = 0xFFFFFF;
	public long created;
	public long modified;
	public boolean visible = true;
	/** Death point (drawn with a skull, always on top of the list). */
	public boolean death;
	/** Death points only: ticks until the dropped items despawn. Only counts down while you are near. -1 = no timer. */
	public int ticksLeft = -1;

	/** 0 = latest death, 1 = old death, 2+ = older. Filled by {@link WorldData#refresh()}; not saved. */
	public transient int deathRank;
	transient int[] bounds;

	public BlockPos pos() {
		return new BlockPos(x, y, z);
	}

	/** Moves the old two-corner area format into {@link #boxes}; makes sure the lists exist. */
	public void migrate() {
		if (blocks == null) {
			blocks = new ArrayList<>();
		}
		if (boxes == null) {
			boxes = new ArrayList<>();
		}
		if (Boolean.TRUE.equals(area) && x2 != null && y2 != null && z2 != null && boxes.isEmpty() && blocks.isEmpty()) {
			boxes.add(new int[] { Math.min(x, x2), Math.min(y, y2), Math.min(z, z2), Math.max(x, x2), Math.max(y, y2), Math.max(z, z2) });
		}
		area = null;
		x2 = null;
		y2 = null;
		z2 = null;
		bounds = null;
	}

	/** True when this waypoint is made of regions (boxes / blocks) instead of one block. */
	public boolean isArea() {
		return !boxes.isEmpty() || !blocks.isEmpty();
	}

	/** Every region as {minX, minY, minZ, maxX, maxY, maxZ}; a single waypoint is one region of one block. */
	public List<int[]> regions() {
		List<int[]> out = new ArrayList<>();
		if (!isArea()) {
			out.add(new int[] { x, y, z, x, y, z });
			return out;
		}
		for (int[] b : boxes) {
			out.add(b.clone());
		}
		for (int[] b : blocks) {
			out.add(new int[] { b[0], b[1], b[2], b[0], b[1], b[2] });
		}
		return out;
	}

	/** Replaces the regions. One single block becomes a normal single waypoint again. */
	public void setRegions(List<int[]> regions) {
		boxes = new ArrayList<>();
		blocks = new ArrayList<>();
		bounds = null;
		if (regions.size() == 1 && isBlock(regions.get(0))) {
			int[] r = regions.get(0);
			x = r[0];
			y = r[1];
			z = r[2];
			return;
		}
		for (int[] r : regions) {
			if (isBlock(r)) {
				blocks.add(new int[] { r[0], r[1], r[2] });
			} else {
				boxes.add(r.clone());
			}
		}
		if (!regions.isEmpty()) {
			int[] first = regions.get(0);
			x = first[0];
			y = first[1];
			z = first[2];
		}
	}

	public static boolean isBlock(int[] r) {
		return r[0] == r[3] && r[1] == r[4] && r[2] == r[5];
	}

	/** Bounding box of all regions: {minX, minY, minZ, maxX, maxY, maxZ}. */
	public int[] bounds() {
		int[] b = bounds;
		if (b == null) {
			b = new int[] { x, y, z, x, y, z };
			if (isArea()) {
				b = null;
				for (int[] r : regions()) {
					if (b == null) {
						b = r.clone();
					} else {
						b[0] = Math.min(b[0], r[0]);
						b[1] = Math.min(b[1], r[1]);
						b[2] = Math.min(b[2], r[2]);
						b[3] = Math.max(b[3], r[3]);
						b[4] = Math.max(b[4], r[4]);
						b[5] = Math.max(b[5], r[5]);
					}
				}
			}
			bounds = b;
		}
		return b;
	}

	public int minX() {
		return bounds()[0];
	}

	public int minY() {
		return bounds()[1];
	}

	public int minZ() {
		return bounds()[2];
	}

	public int maxX() {
		return bounds()[3];
	}

	public int maxY() {
		return bounds()[4];
	}

	public int maxZ() {
		return bounds()[5];
	}

	/** Centre of the bounding box. */
	public Vec3 center() {
		int[] b = bounds();
		return new Vec3((b[0] + b[3] + 1) / 2.0, (b[1] + b[4] + 1) / 2.0, (b[2] + b[5] + 1) / 2.0);
	}

	/** Where the board arrow points: a bit above the top of the bounding box, over its centre (area) or the block (single). */
	public Vec3 boardAnchor() {
		int[] b = bounds();
		return new Vec3((b[0] + b[3] + 1) / 2.0, b[4] + 1.3, (b[2] + b[5] + 1) / 2.0);
	}

	/** Distance from a point to the nearest region (0 inside). */
	public double distanceTo(double px, double py, double pz) {
		double best = Double.MAX_VALUE;
		for (int[] r : regions()) {
			double dx = Math.max(Math.max(r[0] - px, 0), px - (r[3] + 1));
			double dy = Math.max(Math.max(r[1] - py, 0), py - (r[4] + 1));
			double dz = Math.max(Math.max(r[2] - pz, 0), pz - (r[5] + 1));
			best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
		}
		return best;
	}

	public boolean contains(int bx, int by, int bz) {
		for (int[] r : regions()) {
			if (bx >= r[0] && bx <= r[3] && by >= r[1] && by <= r[4] && bz >= r[2] && bz <= r[5]) {
				return true;
			}
		}
		return false;
	}

	/** "3 regions" / "" for single waypoints. */
	public String regionText() {
		if (!isArea()) {
			return "";
		}
		int n = boxes.size() + blocks.size();
		return n + " region" + (n == 1 ? "" : "s");
	}

	/** "12 x 5 x 8" (bounding box) for areas, "" for single waypoints. */
	public String sizeText() {
		if (!isArea()) {
			return "";
		}
		int[] b = bounds();
		return (b[3] - b[0] + 1) + " x " + (b[4] - b[1] + 1) + " x " + (b[5] - b[2] + 1);
	}

	/** Name shown everywhere: death points are called Latest death / Old death. */
	public String title() {
		if (!death) {
			return name;
		}
		return deathRank == 0 ? "Latest death" : deathRank == 1 ? "Old death" : "Old death " + deathRank;
	}

	/** Letter in the board arrow (null = draw a skull). */
	public String initial() {
		if (death) {
			return null;
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (!Character.isWhitespace(c)) {
				return String.valueOf(c).toUpperCase(Locale.ROOT);
			}
		}
		return "W";
	}
}
