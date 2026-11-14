package khoa.tools.client.feature.waypoints;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * The outer surface of the union of boxes (block coordinates, both ends included), without any Minecraft classes so it can be tested on its own.
 * <ul>
 * <li>faces: every exposed block face, merged into as few rectangles as the grid allows (nothing is produced inside the union)</li>
 * <li>edges: every outline edge of the union, found with the 2x2 rule (draw an edge when 1 or 3 of the 4 blocks around it are filled, or 2 diagonal ones),
 * joined into straight segments; each edge carries the direction it should be pushed outward so it is not hidden by the neighbouring blocks</li>
 * </ul>
 * Hole blocks (blocks of other single waypoints) are treated as not part of the union and their faces are not drawn.
 * Works on a compressed grid (only the box boundaries make cuts), so cost depends on the number of boxes, not on their size.
 */
final class RegionGeometry {
	static final long MAX_CELLS = 4_000_000L;

	/** A flat piece of surface on the plane {@code plane} (block coordinate), facing +axis or -axis, covering u0..u1 x v0..v1 (see {@link #uAxis}). */
	record Rect(int axis, boolean positive, int plane, int u0, int u1, int v0, int v1) {
	}

	/** A straight outline segment along {@code axis} from t0 to t1, at lattice position a (first other axis) and b (second other axis); sa / sb = outward push (-1, 0, 1). */
	record Edge(int axis, int a, int b, int t0, int t1, int sa, int sb) {
	}

	record Result(List<Rect> rects, List<Edge> edges) {
	}

	private RegionGeometry() {
	}

	static int uAxis(int axis) {
		return axis == 0 ? 1 : axis == 1 ? 2 : 0;
	}

	static int vAxis(int axis) {
		return axis == 0 ? 2 : axis == 1 ? 0 : 1;
	}

	static int firstOther(int edgeAxis) {
		return edgeAxis == 0 ? 1 : 0;
	}

	static int secondOther(int edgeAxis) {
		return edgeAxis == 2 ? 1 : 2;
	}

	/** Returns null when the grid would be too big (the caller then falls back to something simple). */
	@SuppressWarnings("unchecked")
	static Result build(List<int[]> boxes, List<int[]> holes) {
		if (boxes.isEmpty()) {
			return new Result(List.of(), List.of());
		}
		List<int[]> activeHoles = new ArrayList<>();
		for (int[] h : holes) {
			for (int[] b : boxes) {
				if (h[0] >= b[0] && h[0] <= b[3] && h[1] >= b[1] && h[1] <= b[4] && h[2] >= b[2] && h[2] <= b[5]) {
					activeHoles.add(h);
					break;
				}
			}
		}
		TreeSet<Integer>[] sets = new TreeSet[3];
		for (int k = 0; k < 3; k++) {
			sets[k] = new TreeSet<>();
		}
		for (int[] b : boxes) {
			for (int k = 0; k < 3; k++) {
				sets[k].add(b[k]);
				sets[k].add(b[k + 3] + 1);
			}
		}
		for (int[] h : activeHoles) {
			for (int k = 0; k < 3; k++) {
				sets[k].add(h[k]);
				sets[k].add(h[k] + 1);
			}
		}
		int[][] cuts = new int[3][];
		int[] n = new int[3];
		for (int k = 0; k < 3; k++) {
			cuts[k] = sets[k].stream().mapToInt(Integer::intValue).toArray();
			n[k] = cuts[k].length - 1;
		}
		long total = (long) n[0] * n[1] * n[2];
		if (total > MAX_CELLS) {
			return null;
		}
		Grid grid = new Grid(n, (int) total);
		for (int[] b : boxes) {
			grid.mark(cuts, b, grid.filled, true);
		}
		for (int[] h : activeHoles) {
			grid.mark(cuts, new int[] { h[0], h[1], h[2], h[0], h[1], h[2] }, grid.hole, true);
			grid.mark(cuts, new int[] { h[0], h[1], h[2], h[0], h[1], h[2] }, grid.filled, false);
		}
		List<Rect> rects = new ArrayList<>();
		for (int a = 0; a < 3; a++) {
			faces(grid, cuts, a, rects);
		}
		List<Edge> edges = new ArrayList<>();
		for (int e = 0; e < 3; e++) {
			edges(grid, cuts, e, edges);
		}
		return new Result(rects, edges);
	}

	private static final class Grid {
		final int[] n;
		final boolean[] filled;
		final boolean[] hole;

		Grid(int[] n, int total) {
			this.n = n;
			this.filled = new boolean[total];
			this.hole = new boolean[total];
		}

		int index(int i, int j, int k) {
			return (i * n[1] + j) * n[2] + k;
		}

		boolean get(boolean[] array, int i, int j, int k) {
			return i >= 0 && j >= 0 && k >= 0 && i < n[0] && j < n[1] && k < n[2] && array[index(i, j, k)];
		}

		boolean get(boolean[] array, int[] c) {
			return get(array, c[0], c[1], c[2]);
		}

		void mark(int[][] cuts, int[] box, boolean[] array, boolean value) {
			int[] lo = new int[3];
			int[] hi = new int[3];
			for (int k = 0; k < 3; k++) {
				lo[k] = Arrays.binarySearch(cuts[k], box[k]);
				hi[k] = Arrays.binarySearch(cuts[k], box[k + 3] + 1);
			}
			for (int i = lo[0]; i < hi[0]; i++) {
				for (int j = lo[1]; j < hi[1]; j++) {
					for (int k = lo[2]; k < hi[2]; k++) {
						array[index(i, j, k)] = value;
					}
				}
			}
		}
	}

	private static void faces(Grid grid, int[][] cuts, int a, List<Rect> out) {
		int u = uAxis(a);
		int v = vAxis(a);
		int nu = grid.n[u];
		int nv = grid.n[v];
		for (int p = 0; p <= grid.n[a]; p++) {
			for (int side = 0; side < 2; side++) {
				boolean positive = side == 1;
				boolean[][] exposed = new boolean[nu][nv];
				boolean any = false;
				int[] below = new int[3];
				int[] above = new int[3];
				for (int iu = 0; iu < nu; iu++) {
					for (int iv = 0; iv < nv; iv++) {
						below[a] = p - 1;
						above[a] = p;
						below[u] = iu;
						above[u] = iu;
						below[v] = iv;
						above[v] = iv;
						boolean e = positive
							? grid.get(grid.filled, below) && !grid.get(grid.filled, above) && !grid.get(grid.hole, above)
							: grid.get(grid.filled, above) && !grid.get(grid.filled, below) && !grid.get(grid.hole, below);
						exposed[iu][iv] = e;
						any |= e;
					}
				}
				if (!any) {
					continue;
				}
				for (int iu = 0; iu < nu; iu++) {
					for (int iv = 0; iv < nv; iv++) {
						if (!exposed[iu][iv]) {
							continue;
						}
						int v1 = iv;
						while (v1 < nv && exposed[iu][v1]) {
							v1++;
						}
						int u1 = iu + 1;
						grow:
						while (u1 < nu) {
							for (int k = iv; k < v1; k++) {
								if (!exposed[u1][k]) {
									break grow;
								}
							}
							u1++;
						}
						for (int x = iu; x < u1; x++) {
							for (int k = iv; k < v1; k++) {
								exposed[x][k] = false;
							}
						}
						out.add(new Rect(a, positive, cuts[a][p], cuts[u][iu], cuts[u][u1], cuts[v][iv], cuts[v][v1]));
					}
				}
			}
		}
	}

	private static void edges(Grid grid, int[][] cuts, int e, List<Edge> out) {
		int o1 = firstOther(e);
		int o2 = secondOther(e);
		int[] c = new int[3];
		for (int a = 0; a <= grid.n[o1]; a++) {
			for (int b = 0; b <= grid.n[o2]; b++) {
				int runStart = -1;
				int runPattern = 0;
				int runSa = 0;
				int runSb = 0;
				for (int t = 0; t <= grid.n[e]; t++) {
					int pattern = 0;
					int sa = 0;
					int sb = 0;
					if (t < grid.n[e]) {
						boolean c00 = cell(grid, c, e, t, o1, a - 1, o2, b - 1);
						boolean c10 = cell(grid, c, e, t, o1, a, o2, b - 1);
						boolean c01 = cell(grid, c, e, t, o1, a - 1, o2, b);
						boolean c11 = cell(grid, c, e, t, o1, a, o2, b);
						int filled = (c00 ? 1 : 0) + (c10 ? 1 : 0) + (c01 ? 1 : 0) + (c11 ? 1 : 0);
						boolean draw = filled == 1 || filled == 3 || (filled == 2 && c00 == c11);
						if (draw) {
							pattern = (c00 ? 1 : 0) | (c10 ? 2 : 0) | (c01 ? 4 : 0) | (c11 ? 8 : 0) | 16;
							sa = Integer.signum((c10 ? 0 : 1) + (c11 ? 0 : 1) - (c00 ? 0 : 1) - (c01 ? 0 : 1));
							sb = Integer.signum((c01 ? 0 : 1) + (c11 ? 0 : 1) - (c00 ? 0 : 1) - (c10 ? 0 : 1));
						}
					}
					if (runStart >= 0 && pattern != runPattern) {
						out.add(new Edge(e, cuts[o1][a], cuts[o2][b], cuts[e][runStart], cuts[e][t], runSa, runSb));
						runStart = -1;
					}
					if (runStart < 0 && pattern != 0) {
						runStart = t;
						runPattern = pattern;
						runSa = sa;
						runSb = sb;
					}
				}
			}
		}
	}

	private static boolean cell(Grid grid, int[] c, int e, int t, int o1, int i1, int o2, int i2) {
		c[e] = t;
		c[o1] = i1;
		c[o2] = i2;
		return grid.get(grid.filled, c);
	}
}
