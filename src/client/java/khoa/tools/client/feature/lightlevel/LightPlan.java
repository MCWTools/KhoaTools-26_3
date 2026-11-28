package khoa.tools.client.feature.lightlevel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;

/**
 * One round of "Optimal placement" on a {@link LightScan.Snapshot} (worker thread): works out where light sources
 * should go.
 * <p>
 * Planned spots are kept between rounds. A spot is only replaced when something it did not predict happens to the cells
 * it was going to light (a source put somewhere else, a block placed or removed); a source placed exactly where planned
 * just removes that spot and leaves every other one where it is.
 */
final class LightPlan {
	/** One planned source: the block it goes into and the dark cells it is expected to light. */
	record Spot(long cell, long[] covers) {
		BlockPos pos() {
			return BlockPos.of(cell);
		}
	}

	/** Result of a round, and the memory for the next one. Cells are {@link BlockPos#asLong()} values. */
	record State(LightScan.Settings settings, LightScan.Area area, List<Spot> spots, Set<Long> dark, int uncoverable) {
	}

	/** Longest search of a plan made from nothing, and of a repair of an existing one. */
	private static final long FULL_MILLIS = 5000;
	private static final long LOCAL_MILLIS = 700;

	private LightPlan() {
	}

	/** Worker thread. {@code previous} may be null. */
	static State update(LightScan.Snapshot now, State previous, long seed) {
		LightScan.Settings settings = now.settings();
		if (previous != null && !previous.settings().equals(settings)) {
			previous = null;
		}
		int min = settings.threshold() + 1;
		LightPlanner.Flood flood = new LightPlanner.Flood(now);

		Set<Long> dark = new HashSet<>();
		for (long cell : now.dark()) {
			dark.add(cell);
		}
		boolean[] candidate = new boolean[now.states().length];
		for (int c : now.candidates()) {
			candidate[c] = true;
		}

		List<Spot> kept = new ArrayList<>();
		List<long[]> keptReach = new ArrayList<>();
		if (previous != null) {
			Set<Long> explained = new HashSet<>();
			Set<Long> surprises = new HashSet<>();
			List<Spot> open = new ArrayList<>();
			for (Spot spot : previous.spots()) {
				int at = now.index(spot.cell());
				if (at < 0 || !now.area().contains(spot.cell())) {
					continue;
				}
				if (now.states()[at].getLightEmission() >= settings.emission()) {
					// Placed as planned: what it was going to light is lit, even if the light engine lags a tick behind.
					flood.run(at, settings.emission(), min);
					for (int i = 0; i < flood.reachedCount; i++) {
						explained.add(now.cell(flood.reached[i]));
					}
					explained.add(spot.cell());
				} else if (!candidate[at]) {
					for (long cell : spot.covers()) {
						surprises.add(cell);
					}
				} else {
					open.add(spot);
				}
			}
			dark.removeAll(explained);

			LightScan.Area before = previous.area();
			Set<Long> fresh = new HashSet<>();
			for (long cell : dark) {
				if (!before.contains(cell)) {
					fresh.add(cell);
				} else if (!previous.dark().contains(cell)) {
					surprises.add(cell);
				}
			}
			for (long cell : previous.dark()) {
				if (now.area().contains(cell) && !dark.contains(cell) && !explained.contains(cell)) {
					surprises.add(cell);
				}
			}
			surprises.removeAll(explained);

			int reach = settings.emission() - min;
			for (Spot spot : open) {
				flood.run(now.index(spot.cell()), settings.emission(), min);
				long[] covers = darkReached(now, flood, dark);
				if (covers.length == 0 || touches(covers, surprises) || touches(spot.covers(), surprises)) {
					continue;
				}
				// A spot that sat at the rim of the old area was planned without knowing what lies beyond.
				int x = BlockPos.getX(spot.cell());
				int z = BlockPos.getZ(spot.cell());
				int rim = Math.min(Math.min(x - before.minX(), before.maxX() - x), Math.min(z - before.minZ(), before.maxZ() - z));
				if (rim <= reach / 2 && touches(covers, fresh)) {
					continue;
				}
				kept.add(new Spot(spot.cell(), covers));
				keptReach.add(covers);
			}
		}

		Set<Long> need = new HashSet<>(dark);
		for (long[] covers : keptReach) {
			for (long cell : covers) {
				need.remove(cell);
			}
		}
		Set<Long> taken = new HashSet<>();
		for (Spot spot : kept) {
			taken.add(spot.cell());
		}
		int[] targets = need.stream().mapToInt(now::index).sorted().toArray();
		int[] candidates = Arrays.stream(now.candidates()).filter(c -> !taken.contains(now.cell(c))).toArray();
		LightPlanner.Result result = LightPlanner.solve(now, candidates, targets, settings.emission(), settings.threshold(),
			kept.isEmpty() ? FULL_MILLIS : LOCAL_MILLIS, seed);

		List<Spot> spots = new ArrayList<>(kept);
		for (int source : result.sources()) {
			flood.run(source, settings.emission(), min);
			spots.add(new Spot(now.cell(source), darkReached(now, flood, dark)));
		}
		removeRedundant(spots, kept.size());
		return new State(settings, now.area(), List.copyOf(spots), dark, result.uncoverable());
	}

	private static long[] darkReached(LightScan.Snapshot now, LightPlanner.Flood flood, Set<Long> dark) {
		long[] out = new long[flood.reachedCount];
		int n = 0;
		for (int i = 0; i < flood.reachedCount; i++) {
			long cell = now.cell(flood.reached[i]);
			if (dark.contains(cell)) {
				out[n++] = cell;
			}
		}
		return Arrays.copyOf(out, n);
	}

	private static boolean touches(long[] cells, Set<Long> set) {
		if (set.isEmpty()) {
			return false;
		}
		for (long cell : cells) {
			if (set.contains(cell)) {
				return true;
			}
		}
		return false;
	}

	/** New spots can make an old one unnecessary: drop old spots whose cells are all lit by another planned spot. */
	private static void removeRedundant(List<Spot> spots, int oldCount) {
		java.util.Map<Long, Integer> lit = new java.util.HashMap<>();
		for (Spot spot : spots) {
			for (long cell : spot.covers()) {
				lit.merge(cell, 1, Integer::sum);
			}
		}
		for (int i = oldCount - 1; i >= 0; i--) {
			long[] covers = spots.get(i).covers();
			boolean needed = false;
			for (long cell : covers) {
				if (lit.get(cell) < 2) {
					needed = true;
					break;
				}
			}
			if (!needed) {
				for (long cell : covers) {
					lit.merge(cell, -1, Integer::sum);
				}
				spots.remove(i);
			}
		}
	}
}
