package khoa.tools.client.feature.lightlevel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Pure search core of "Optimal placement" (no Minecraft types): picks as few light source positions as it can so that
 * every target cell gets a block light of at least {@code threshold + 1}.
 * <p>
 * This is a set cover problem (NP hard), so the result is a good solution, not a proven minimum:
 * weighted greedy with a few weightings, then local search (throw some sources away, refill, keep it when not worse),
 * then every chosen source is moved to the middle of the cells only it lights.
 * Block light is the maximum over all sources, so the coverage of one source does not depend on the others.
 */
final class LightPlanner {
	/** The voxels around the planned area. Indices are {@code x + sx * (z + sz * y)}. */
	interface Grid {
		int sizeX();

		int sizeY();

		int sizeZ();

		/** Light levels lost when entering this cell (1..15). */
		int cost(int to);

		/** True when the faces between the two neighbour cells stop all light. {@code dir} as in {@link #DX}. */
		boolean blocked(int from, int to, int dir);
	}

	/** Direction offsets in the order of Minecraft's Direction: down, up, north, south, west, east. */
	static final int[] DX = { 0, 0, 0, 0, -1, 1 };
	static final int[] DY = { -1, 1, 0, 0, 0, 0 };
	static final int[] DZ = { 0, 0, -1, 1, 0, 0 };

	/** Cells one source lights well enough, with the level each one gets. */
	record Reach(int[] cells, byte[] levels) {
	}

	/** Light flood from one position. One instance per thread (reuses its buffers). */
	static final class Flood {
		private final Grid grid;
		private final int[] stamp;
		private final byte[] level;
		private final int[][] queue = new int[16][64];
		private final int[] count = new int[16];
		private int generation;
		/** Cells reached by the last {@link #run}, in order of falling light. */
		int[] reached = new int[256];
		int reachedCount;

		Flood(Grid grid) {
			this.grid = grid;
			int volume = grid.sizeX() * grid.sizeY() * grid.sizeZ();
			stamp = new int[volume];
			level = new byte[volume];
		}

		/** Floods from {@code source} and keeps every cell that ends with at least {@code min} light. */
		void run(int source, int emission, int min) {
			generation++;
			reachedCount = 0;
			if (emission < min || min < 1) {
				return;
			}
			Arrays.fill(count, 0);
			int sx = grid.sizeX();
			int sy = grid.sizeY();
			int sz = grid.sizeZ();
			stamp[source] = generation;
			level[source] = (byte) emission;
			push(emission, source);
			for (int value = emission; value >= min; value--) {
				for (int i = 0; i < count[value]; i++) {
					int cell = queue[value][i];
					if (level[cell] != value) {
						continue;
					}
					append(cell);
					if (value - 1 < min) {
						continue;
					}
					int x = cell % sx;
					int z = cell / sx % sz;
					int y = cell / sx / sz;
					for (int d = 0; d < 6; d++) {
						int nx = x + DX[d];
						int ny = y + DY[d];
						int nz = z + DZ[d];
						if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz) {
							continue;
						}
						int next = nx + sx * (nz + sz * ny);
						int old = stamp[next] == generation ? level[next] : 0;
						if (old >= value - 1) {
							continue;
						}
						int result = value - grid.cost(next);
						if (result < min || result <= old || grid.blocked(cell, next, d)) {
							continue;
						}
						stamp[next] = generation;
						level[next] = (byte) result;
						push(result, next);
					}
				}
			}
		}

		/** Light the last flood gave this cell, 0 when it was not reached. */
		int level(int cell) {
			return stamp[cell] == generation ? level[cell] : 0;
		}

		private void push(int value, int cell) {
			if (count[value] == queue[value].length) {
				queue[value] = Arrays.copyOf(queue[value], queue[value].length * 2);
			}
			queue[value][count[value]++] = cell;
		}

		private void append(int cell) {
			if (reachedCount == reached.length) {
				reached = Arrays.copyOf(reached, reached.length * 2);
			}
			reached[reachedCount++] = cell;
		}
	}

	/** {@code sources} = chosen cells, {@code uncoverable} = targets no candidate can light well enough. */
	record Result(int[] sources, int uncoverable) {
	}

	/** Above this many candidates only every second one (checkerboard) is tried, to keep big areas fast. */
	private static final int THIN_ABOVE = 7000;

	private final int[][] cover;
	private final byte[][] margin;
	private final int[][] coveredBy;
	private final int[] candidates;
	private final int targetCount;
	private final Random random;

	/** Current solution: chosen candidate numbers and how many of them light each target. */
	private final List<Integer> chosen = new ArrayList<>();
	private final int[] lit;
	private final boolean[] used;

	private LightPlanner(int[] candidates, int[][] cover, byte[][] margin, int targetCount, long seed) {
		this.candidates = candidates;
		this.cover = cover;
		this.margin = margin;
		this.targetCount = targetCount;
		this.random = new Random(seed);
		int[] sizes = new int[targetCount];
		for (int[] list : cover) {
			for (int t : list) {
				sizes[t]++;
			}
		}
		coveredBy = new int[targetCount][];
		for (int t = 0; t < targetCount; t++) {
			coveredBy[t] = new int[sizes[t]];
		}
		int[] fill = new int[targetCount];
		for (int c = 0; c < cover.length; c++) {
			for (int t : cover[c]) {
				coveredBy[t][fill[t]++] = c;
			}
		}
		lit = new int[targetCount];
		used = new boolean[candidates.length];
	}

	/**
	 * @param candidates cells where a source may be placed
	 * @param targets    cells that must end up with at least {@code threshold + 1} light
	 * @param millis     time budget of the local search
	 */
	static Result solve(Grid grid, int[] candidates, int[] targets, int emission, int threshold, long millis, long seed) {
		if (targets.length == 0) {
			return new Result(new int[0], 0);
		}
		int volume = grid.sizeX() * grid.sizeY() * grid.sizeZ();
		int[] targetOf = new int[volume];
		Arrays.fill(targetOf, -1);
		for (int i = 0; i < targets.length; i++) {
			targetOf[targets[i]] = i;
		}
		int[] usable = near(grid, candidates, targets, emission);
		// Big area: the first half of the list is one colour of the checkerboard, the rest is only a fallback.
		int firstChoice = usable.length;
		if (usable.length > THIN_ABOVE) {
			int sx = grid.sizeX();
			int sz = grid.sizeZ();
			int[] even = Arrays.stream(usable).filter(c -> ((c % sx + c / sx % sz) & 1) == 0).toArray();
			int[] odd = Arrays.stream(usable).filter(c -> ((c % sx + c / sx % sz) & 1) != 0).toArray();
			firstChoice = even.length;
			usable = Arrays.copyOf(even, even.length + odd.length);
			System.arraycopy(odd, 0, usable, even.length, odd.length);
		}

		Flood flood = new Flood(grid);
		List<Integer> kept = new ArrayList<>();
		List<int[]> covers = new ArrayList<>();
		List<byte[]> margins = new ArrayList<>();
		boolean[] reachable = new boolean[targets.length];
		int[] tmp = new int[targets.length];
		byte[] tmpLevel = new byte[targets.length];
		boolean[] primary = null;
		for (int position = 0; position < usable.length; position++) {
			if (Thread.currentThread().isInterrupted()) {
				return new Result(new int[0], 0);
			}
			if (position == firstChoice) {
				primary = reachable.clone();
			}
			int c = usable[position];
			flood.run(c, emission, threshold + 1);
			int n = 0;
			boolean wanted = primary == null;
			for (int i = 0; i < flood.reachedCount; i++) {
				int t = targetOf[flood.reached[i]];
				if (t >= 0) {
					tmp[n] = t;
					tmpLevel[n++] = (byte) flood.level(flood.reached[i]);
					reachable[t] = true;
					// A skipped candidate is only needed for a target none of the first choices can light.
					wanted |= primary != null && !primary[t];
				}
			}
			if (n > 0 && wanted) {
				kept.add(c);
				covers.add(Arrays.copyOf(tmp, n));
				margins.add(Arrays.copyOf(tmpLevel, n));
			}
		}
		int uncoverable = 0;
		for (boolean r : reachable) {
			if (!r) {
				uncoverable++;
			}
		}
		if (kept.isEmpty()) {
			return new Result(new int[0], uncoverable);
		}
		LightPlanner planner = new LightPlanner(kept.stream().mapToInt(Integer::intValue).toArray(), covers.toArray(int[][]::new),
			margins.toArray(byte[][]::new), targets.length, seed);
		return new Result(planner.search(millis), uncoverable);
	}

	/** Candidates close enough (box distance on 8 block buckets) to some target to matter at all. */
	private static int[] near(Grid grid, int[] candidates, int[] targets, int emission) {
		int sx = grid.sizeX();
		int sz = grid.sizeZ();
		int bx = (sx >> 3) + 1;
		int by = (grid.sizeY() >> 3) + 1;
		int bz = (sz >> 3) + 1;
		boolean[] has = new boolean[bx * by * bz];
		for (int t : targets) {
			has[(t % sx >> 3) + bx * ((t / sx % sz >> 3) + bz * (t / sx / sz >> 3))] = true;
		}
		int span = (emission + 7) >> 3;
		int[] out = new int[candidates.length];
		int n = 0;
		search:
		for (int c : candidates) {
			int x = c % sx >> 3;
			int z = c / sx % sz >> 3;
			int y = c / sx / sz >> 3;
			for (int ix = Math.max(0, x - span); ix <= Math.min(bx - 1, x + span); ix++) {
				for (int iy = Math.max(0, y - span); iy <= Math.min(by - 1, y + span); iy++) {
					for (int iz = Math.max(0, z - span); iz <= Math.min(bz - 1, z + span); iz++) {
						if (has[ix + bx * (iz + bz * iy)]) {
							out[n++] = c;
							continue search;
						}
					}
				}
			}
		}
		return Arrays.copyOf(out, n);
	}

	private int[] search(long millis) {
		long end = System.nanoTime() + millis * 1_000_000L;
		List<Integer> best = null;
		// Hard-to-reach targets (corners, edges) weigh more with a higher alpha: the sources then pack from the border inwards.
		for (double alpha : new double[] { 1.0, 0.5, 0.0, 2.0 }) {
			clear();
			greedy(weights(alpha), 0.0);
			prune();
			if (best == null || chosen.size() < best.size()) {
				best = new ArrayList<>(chosen);
			}
			if (System.nanoTime() > end) {
				break;
			}
		}
		load(best);

		double[] weight = weights(1.0);
		int stale = 0;
		while (chosen.size() > 1 && stale < 4000 && System.nanoTime() < end && !Thread.currentThread().isInterrupted()) {
			List<Integer> before = new ArrayList<>(chosen);
			ruin(2 + random.nextInt(3));
			greedy(weight, 0.4);
			prune();
			if (chosen.size() > before.size()) {
				load(before);
				stale++;
			} else if (chosen.size() < before.size()) {
				stale = 0;
			} else {
				stale++;
			}
		}
		center();
		prune();
		return chosen.stream().mapToInt(c -> candidates[c]).toArray();
	}

	private double[] weights(double alpha) {
		double[] weight = new double[targetCount];
		for (int t = 0; t < targetCount; t++) {
			weight[t] = coveredBy[t].length == 0 ? 0 : Math.pow(coveredBy[t].length, -alpha);
		}
		return weight;
	}

	private void clear() {
		chosen.clear();
		Arrays.fill(lit, 0);
		Arrays.fill(used, false);
	}

	private void load(List<Integer> solution) {
		clear();
		for (int c : solution) {
			add(c);
		}
	}

	private void add(int c) {
		chosen.add(c);
		used[c] = true;
		for (int t : cover[c]) {
			lit[t]++;
		}
	}

	private void remove(int position) {
		int c = chosen.remove(position);
		used[c] = false;
		for (int t : cover[c]) {
			lit[t]--;
		}
	}

	private record Entry(int candidate, double score) {
	}

	private double gain(int c, double[] weight) {
		double sum = 0;
		for (int t : cover[c]) {
			if (lit[t] == 0) {
				sum += weight[t];
			}
		}
		return sum;
	}

	/** Lights everything still dark. Lazy greedy: a gain can only shrink, so stale queue entries are re-scored on top. */
	private void greedy(double[] weight, double noise) {
		boolean[] seen = new boolean[candidates.length];
		double[] factor = new double[candidates.length];
		PriorityQueue<Entry> queue = new PriorityQueue<>((a, b) -> Double.compare(b.score(), a.score()));
		for (int t = 0; t < targetCount; t++) {
			if (lit[t] != 0) {
				continue;
			}
			for (int c : coveredBy[t]) {
				if (!seen[c] && !used[c]) {
					seen[c] = true;
					factor[c] = 1.0 + noise * random.nextDouble();
					queue.add(new Entry(c, gain(c, weight) * factor[c]));
				}
			}
		}
		while (!queue.isEmpty()) {
			Entry top = queue.poll();
			double now = gain(top.candidate(), weight) * factor[top.candidate()];
			if (now <= 0) {
				continue;
			}
			Entry next = queue.peek();
			if (next != null && now < next.score() - 1e-12) {
				queue.add(new Entry(top.candidate(), now));
				continue;
			}
			add(top.candidate());
		}
	}

	/** Drops sources whose cells are all lit by another one too, smallest first. */
	private void prune() {
		List<Integer> order = new ArrayList<>(chosen);
		order.sort((a, b) -> Integer.compare(cover[a].length, cover[b].length));
		next:
		for (int c : order) {
			for (int t : cover[c]) {
				if (lit[t] < 2) {
					continue next;
				}
			}
			remove(chosen.indexOf(c));
		}
	}

	/** Throws away one random source and the ones that share the most cells with it. */
	private void ruin(int size) {
		int first = chosen.get(random.nextInt(chosen.size()));
		boolean[] mine = new boolean[targetCount];
		for (int t : cover[first]) {
			mine[t] = true;
		}
		List<int[]> shared = new ArrayList<>();
		for (int c : chosen) {
			if (c == first) {
				continue;
			}
			// Overlap, or at least closeness through a common candidate area: count targets lit by both.
			int n = 0;
			for (int t : cover[c]) {
				if (mine[t]) {
					n++;
				}
			}
			if (n > 0) {
				shared.add(new int[] { c, n * 4 + random.nextInt(4 + cover[c].length / 4) });
			}
		}
		shared.sort((a, b) -> Integer.compare(b[1], a[1]));
		remove(chosen.indexOf(first));
		for (int i = 0; i < Math.min(size - 1, shared.size()); i++) {
			remove(chosen.indexOf(shared.get(i)[0]));
		}
	}

	/**
	 * Moves every source to the best middle: it must still light everything only it lights; among those positions the one
	 * whose weakest such cell is brightest wins (then total brightness, then number of targets). One dark patch in a lit
	 * area therefore gets its source in the centre of the patch.
	 */
	private void center() {
		int[] need = new int[targetCount];
		int mark = 0;
		for (int i = 0; i < chosen.size(); i++) {
			int current = chosen.get(i);
			mark++;
			int needed = 0;
			int rarest = -1;
			for (int t : cover[current]) {
				if (lit[t] == 1) {
					need[t] = mark;
					needed++;
					if (rarest < 0 || coveredBy[t].length < coveredBy[rarest].length) {
						rarest = t;
					}
				}
			}
			if (needed == 0) {
				continue;
			}
			int best = current;
			long bestScore = centerScore(current, need, mark, needed);
			for (int c : coveredBy[rarest]) {
				if (used[c]) {
					continue;
				}
				long score = centerScore(c, need, mark, needed);
				if (score > bestScore) {
					best = c;
					bestScore = score;
				}
			}
			if (best != current) {
				used[current] = false;
				used[best] = true;
				for (int t : cover[current]) {
					lit[t]--;
				}
				for (int t : cover[best]) {
					lit[t]++;
				}
				chosen.set(i, best);
			}
		}
	}

	private long centerScore(int c, int[] need, int mark, int needed) {
		int found = 0;
		int weakest = 99;
		long sum = 0;
		for (int i = 0; i < cover[c].length; i++) {
			if (need[cover[c][i]] == mark) {
				found++;
				weakest = Math.min(weakest, margin[c][i]);
				sum += margin[c][i];
			}
		}
		if (found < needed) {
			return -1;
		}
		return weakest * 1_000_000_000L + sum * 100_000L + cover[c].length;
	}
}
