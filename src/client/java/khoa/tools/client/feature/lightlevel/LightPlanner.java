package khoa.tools.client.feature.lightlevel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Pure search core of "Optimal placement" (no Minecraft types): picks as few light source positions as it can so that
 * every target cell gets a block light of at least {@code threshold + 1}.
 * <p>
 * This is a set cover problem (NP hard), so a minimum cannot be promised. Weighted greedy with a few weightings gives
 * a start; then a row weighting local search keeps removing one source and swapping the others until everything is lit
 * again (cells that stay dark grow heavier, which pushes the search out of dead ends). It stops early when it reaches a
 * proven lower bound. At the end every chosen source is moved to the middle of the cells only it lights.
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

	/** Roughly how many (candidate, lit cell) pairs are kept; bigger areas try only an evenly spread part of the candidates. */
	private static final long MAX_PAIRS = 6_000_000L;
	private static final int MIN_CANDIDATES = 6000;
	/** The local search gives up after this long without finding anything better. */
	private static final long STALL_MILLIS = 1500;

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
		Flood flood = new Flood(grid);
		// How many targets does a candidate light on average? Decides how many candidates fit in memory.
		long pairs = 0;
		int samples = Math.min(48, usable.length);
		for (int i = 0; i < samples; i++) {
			flood.run(usable[(int) ((long) i * usable.length / samples)], emission, threshold + 1);
			for (int k = 0; k < flood.reachedCount; k++) {
				if (targetOf[flood.reached[k]] >= 0) {
					pairs++;
				}
			}
		}
		long limit = Math.max(MIN_CANDIDATES, MAX_PAIRS * Math.max(1, samples) / Math.max(1, pairs));
		int step = (int) Math.max(1, (usable.length + limit - 1) / limit);
		int firstChoice = usable.length;
		if (step > 1) {
			// An even lattice over x / z; the other candidates are only a fallback for cells the lattice cannot light.
			int sx = grid.sizeX();
			int sz = grid.sizeZ();
			int skew = Math.max(1, (int) Math.round(Math.sqrt(step)));
			int by = step;
			int[] first = Arrays.stream(usable).filter(c -> (c % sx + (c / sx % sz) * skew) % by == 0).toArray();
			int[] rest = Arrays.stream(usable).filter(c -> (c % sx + (c / sx % sz) * skew) % by != 0).toArray();
			firstChoice = first.length;
			usable = Arrays.copyOf(first, first.length + rest.length);
			System.arraycopy(rest, 0, usable, first.length, rest.length);
		}

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
				// Only candidates near a still unlit target are worth flooding.
				boolean[] done = primary;
				int[] unlit = IntStream.range(0, targets.length).filter(t -> !done[t]).map(t -> targets[t]).toArray();
				if (unlit.length == 0) {
					break;
				}
				int[] rest = near(grid, Arrays.copyOfRange(usable, position, usable.length), unlit, emission);
				System.arraycopy(rest, 0, usable, position, rest.length);
				usable = Arrays.copyOf(usable, position + rest.length);
				if (rest.length == 0) {
					break;
				}
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
		best = improve(best, lowerBound(), end);
		load(best);
		center();
		prune();
		return chosen.stream().mapToInt(c -> candidates[c]).toArray();
	}

	/** Targets no two of which share a candidate each need their own source: so many are needed at least. */
	private int lowerBound() {
		Integer[] order = new Integer[targetCount];
		for (int t = 0; t < targetCount; t++) {
			order[t] = t;
		}
		Arrays.sort(order, (a, b) -> Integer.compare(coveredBy[a].length, coveredBy[b].length));
		boolean[] taken = new boolean[candidates.length];
		int bound = 0;
		next:
		for (int t : order) {
			if (coveredBy[t].length == 0) {
				continue;
			}
			for (int c : coveredBy[t]) {
				if (taken[c]) {
					continue next;
				}
			}
			for (int c : coveredBy[t]) {
				taken[c] = true;
			}
			bound++;
		}
		return bound;
	}

	/** Row weighting local search state, only alive inside {@link #improve}. */
	private final class Weighted {
		/** Weight of a target: grows every step it stays dark. */
		final int[] weight = new int[targetCount];
		/** Chosen candidate: minus the weight only it holds up. Other candidate: the dark weight it would light. */
		final long[] score = new long[candidates.length];
		/** XOR of the chosen candidates lighting a target, so with exactly one it names it. */
		final int[] owner = new int[targetCount];
		final long[] age = new long[candidates.length];
		final int[] dark = new int[targetCount];
		final int[] darkAt = new int[targetCount];
		int darkCount;

		Weighted() {
			Arrays.fill(weight, 1);
			Arrays.fill(lit, 0);
			Arrays.fill(used, false);
			for (int c = 0; c < candidates.length; c++) {
				score[c] = cover[c].length;
			}
			for (int t = 0; t < targetCount; t++) {
				if (coveredBy[t].length > 0) {
					darkAt[t] = darkCount;
					dark[darkCount++] = t;
				}
			}
			List<Integer> start = new ArrayList<>(chosen);
			chosen.clear();
			for (int c : start) {
				put(c);
			}
		}

		void put(int c) {
			chosen.add(c);
			used[c] = true;
			long own = 0;
			for (int t : cover[c]) {
				if (lit[t] == 0) {
					for (int d : coveredBy[t]) {
						if (!used[d]) {
							score[d] -= weight[t];
						}
					}
					int last = dark[--darkCount];
					dark[darkAt[t]] = last;
					darkAt[last] = darkAt[t];
					own += weight[t];
				} else if (lit[t] == 1) {
					score[owner[t]] += weight[t];
				}
				lit[t]++;
				owner[t] ^= c;
			}
			score[c] = -own;
		}

		void take(int position) {
			int c = chosen.get(position);
			int last = chosen.remove(chosen.size() - 1);
			if (position < chosen.size()) {
				chosen.set(position, last);
			}
			used[c] = false;
			long gain = 0;
			for (int t : cover[c]) {
				lit[t]--;
				owner[t] ^= c;
				if (lit[t] == 0) {
					for (int d : coveredBy[t]) {
						if (!used[d] && d != c) {
							score[d] += weight[t];
						}
					}
					darkAt[t] = darkCount;
					dark[darkCount++] = t;
					gain += weight[t];
				} else if (lit[t] == 1) {
					score[owner[t]] -= weight[t];
				}
			}
			score[c] = gain;
		}

		/** Position in {@code chosen} of the source whose loss hurts least (a sample of them when there are many). */
		int cheapest(int avoid) {
			int size = chosen.size();
			int tries = Math.min(size, 120);
			int best = -1;
			for (int i = 0; i < tries; i++) {
				int p = size <= 120 ? i : random.nextInt(size);
				int c = chosen.get(p);
				if (c == avoid) {
					continue;
				}
				if (best < 0 || score[c] > score[chosen.get(best)] || score[c] == score[chosen.get(best)] && age[c] < age[chosen.get(best)]) {
					best = p;
				}
			}
			return best;
		}

		void age() {
			for (int i = 0; i < darkCount; i++) {
				int t = dark[i];
				weight[t]++;
				for (int d : coveredBy[t]) {
					if (!used[d]) {
						score[d]++;
					}
				}
			}
		}
	}

	/** Keeps asking for one source less than the best full cover found so far. */
	private List<Integer> improve(List<Integer> start, int bound, long end) {
		List<Integer> best = start;
		if (best.size() <= bound) {
			return best;
		}
		Weighted w = new Weighted();
		long found = System.nanoTime();
		int removed = -1;
		for (long step = 1;; step++) {
			if (w.darkCount == 0) {
				if (chosen.size() < best.size()) {
					best = new ArrayList<>(chosen);
					found = System.nanoTime();
				}
				if (chosen.size() <= bound || chosen.isEmpty()) {
					break;
				}
				w.take(w.cheapest(-1));
				continue;
			}
			if ((step & 31) == 0) {
				long now = System.nanoTime();
				if (now > end || now - found > STALL_MILLIS * 1_000_000L || Thread.currentThread().isInterrupted()) {
					break;
				}
			}
			// Swap: drop the cheapest source, then light one random dark target with the best candidate for it.
			int added = -1;
			if (!chosen.isEmpty()) {
				int position = w.cheapest(-1);
				removed = chosen.get(position);
				w.take(position);
				w.age[removed] = step;
			}
			int t = w.dark[random.nextInt(w.darkCount)];
			for (int c : coveredBy[t]) {
				if (used[c] || c == removed && coveredBy[t].length > 1) {
					continue;
				}
				if (added < 0 || w.score[c] > w.score[added] || w.score[c] == w.score[added] && w.age[c] < w.age[added]) {
					added = c;
				}
			}
			if (added >= 0) {
				w.put(added);
				w.age[added] = step;
			}
			w.age();
		}
		return best;
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
