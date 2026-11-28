package khoa.tools.client.feature.lightlevel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * One round of "Optimal placement": copy the blocks and block light around the player on the client thread
 * ({@link #capture}), then work out on a worker thread where light sources should go ({@link #update}).
 * <p>
 * Planned spots are kept between rounds. A spot is only replaced when something it did not predict happens to the cells
 * it was going to light (a source put somewhere else, a block placed or removed); a source placed exactly where planned
 * just removes that spot and leaves every other one where it is.
 */
final class LightPlan {
	/** What is planned and where. Two plans with different values here never share spots. */
	record Settings(int emission, boolean fullBlock, int threshold) {
	}

	/** Surfaces (top face of the floor block) within +-radius horizontally and +-height vertically of the centre. */
	record Region(int x, int y, int z, int radius, int height) {
		/** {@code cell} = the free block above the floor. */
		boolean contains(long cell) {
			return Math.abs(BlockPos.getX(cell) - x) <= radius && Math.abs(BlockPos.getZ(cell) - z) <= radius
				&& Math.abs(BlockPos.getY(cell) - 1 - y) <= height;
		}
	}

	/** One planned source: the block it goes into and the dark cells it is expected to light. */
	record Spot(long cell, long[] covers) {
		BlockPos pos() {
			return BlockPos.of(cell);
		}
	}

	/** Result of a round, and the memory for the next one. Cells are {@link BlockPos#asLong()} values. */
	record State(Settings settings, Region region, List<Spot> spots, Set<Long> dark, int uncoverable) {
	}

	/** Copy of the world: nothing in here is touched by the game afterwards. */
	record Snapshot(Settings settings, Region region, int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ,
		BlockState[] states, long[] dark, int[] candidates) implements LightPlanner.Grid {
		@Override
		public int cost(int to) {
			return Math.max(1, states[to].getLightDampening());
		}

		@Override
		public boolean blocked(int from, int to, int dir) {
			Direction direction = Direction.from3DDataValue(dir);
			return Shapes.faceShapeOccludes(LightEngine.getOcclusionShape(states[from], direction),
				LightEngine.getOcclusionShape(states[to], direction.getOpposite()));
		}

		/** Grid index of a world cell, -1 when it is outside the copy. */
		int index(long cell) {
			int x = BlockPos.getX(cell) - originX;
			int y = BlockPos.getY(cell) - originY;
			int z = BlockPos.getZ(cell) - originZ;
			if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
				return -1;
			}
			return x + sizeX * (z + sizeZ * y);
		}

		long cell(int index) {
			return BlockPos.asLong(originX + index % sizeX, originY + index / sizeX / sizeZ, originZ + index / sizeX % sizeZ);
		}
	}

	/** Search time of a plan made from nothing, and of a repair of an existing one. */
	private static final long FULL_MILLIS = 900;
	private static final long LOCAL_MILLIS = 300;

	private LightPlan() {
	}

	/** Client thread only. */
	static Snapshot capture(ClientLevel world, Region region, Settings settings) {
		// A flood may take a detour just outside the region, so copy a source's whole reach around it.
		int pad = settings.emission() + 1;
		int originX = region.x() - region.radius() - pad;
		int originZ = region.z() - region.radius() - pad;
		int originY = region.y() - region.height() - pad;
		int sizeX = 2 * (region.radius() + pad) + 1;
		int sizeY = 2 * (region.height() + pad) + 2;
		int sizeZ = sizeX;
		int minY = world.getMinY();
		int maxY = minY + world.getHeight() - 1;

		BlockState[] states = new BlockState[sizeX * sizeY * sizeZ];
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState wall = Blocks.BEDROCK.defaultBlockState();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = 0; x < sizeX; x++) {
			for (int z = 0; z < sizeZ; z++) {
				int wx = originX + x;
				int wz = originZ + z;
				LevelChunk chunk = world.hasChunk(wx >> 4, wz >> 4) ? world.getChunk(wx >> 4, wz >> 4) : null;
				for (int y = 0; y < sizeY; y++) {
					int wy = originY + y;
					states[x + sizeX * (z + sizeZ * y)] = chunk == null ? wall : wy < minY || wy > maxY ? air : chunk.getBlockState(pos.set(wx, wy, wz));
				}
			}
		}

		LayerLightEventListener light = world.getLightEngine().getLayerListener(LightLayer.BLOCK);
		int y0 = Math.max(minY, region.y() - region.height());
		int y1 = Math.min(maxY - 1, region.y() + region.height());
		long[] dark = new long[1024];
		int[] candidates = new int[1024];
		int darkCount = 0;
		int candidateCount = 0;
		BlockPos.MutableBlockPos floor = new BlockPos.MutableBlockPos();
		for (int wx = region.x() - region.radius(); wx <= region.x() + region.radius(); wx++) {
			for (int wz = region.z() - region.radius(); wz <= region.z() + region.radius(); wz++) {
				if (!world.hasChunk(wx >> 4, wz >> 4)) {
					continue;
				}
				for (int y = y0; y <= y1; y++) {
					int below = wx - originX + sizeX * (wz - originZ + sizeZ * (y - originY));
					int above = below + sizeX * sizeZ;
					BlockState floorState = states[below];
					if (floorState.isAir()) {
						continue;
					}
					BlockState cellState = states[above];
					pos.set(wx, y + 1, wz);
					floor.set(wx, y, wz);
					if (!LightLevelOverlayFeature.isFree(world, cellState, pos) || !floorState.isFaceSturdy(world, floor, Direction.UP)) {
						continue;
					}
					if (light.getLightValue(pos) <= settings.threshold()) {
						if (darkCount == dark.length) {
							dark = Arrays.copyOf(dark, darkCount * 2);
						}
						dark[darkCount++] = pos.asLong();
					}
					if (cellState.canBeReplaced() && cellState.getFluidState().isEmpty()
						&& (settings.fullBlock() || Block.canSupportCenter(world, floor, Direction.UP))) {
						if (candidateCount == candidates.length) {
							candidates = Arrays.copyOf(candidates, candidateCount * 2);
						}
						candidates[candidateCount++] = above;
					}
				}
			}
		}
		return new Snapshot(settings, region, originX, originY, originZ, sizeX, sizeY, sizeZ, states,
			Arrays.copyOf(dark, darkCount), Arrays.copyOf(candidates, candidateCount));
	}

	/** Worker thread. {@code previous} may be null. */
	static State update(Snapshot now, State previous, long seed) {
		Settings settings = now.settings();
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
				if (at < 0 || !now.region().contains(spot.cell())) {
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

			Region before = previous.region();
			Set<Long> fresh = new HashSet<>();
			for (long cell : dark) {
				if (!before.contains(cell)) {
					fresh.add(cell);
				} else if (!previous.dark().contains(cell)) {
					surprises.add(cell);
				}
			}
			for (long cell : previous.dark()) {
				if (now.region().contains(cell) && !dark.contains(cell) && !explained.contains(cell)) {
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
				int rim = Math.min(before.radius() - Math.abs(BlockPos.getX(spot.cell()) - before.x()),
					before.radius() - Math.abs(BlockPos.getZ(spot.cell()) - before.z()));
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
		return new State(settings, now.region(), List.copyOf(spots), dark, result.uncoverable());
	}

	private static long[] darkReached(Snapshot now, LightPlanner.Flood flood, Set<Long> dark) {
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
