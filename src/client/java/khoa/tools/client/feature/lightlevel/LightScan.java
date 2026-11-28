package khoa.tools.client.feature.lightlevel;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * Finds the surfaces at or below the light level inside an area. The client thread only copies chunk sections
 * ({@link #copy}, cheap); the scan itself ({@link #scan}) runs on a worker thread, so tall areas do not stutter the game.
 */
final class LightScan {
	/** Blocks copied around the area: a flood may leave it and come back, and no source reaches further. */
	private static final int PAD = 16;

	/** Area in block coordinates, all inclusive. Y is the block a mob (or the torch) is in, not the floor under it. */
	record Area(int minX, int maxX, int minZ, int maxZ, int minY, int maxY) {
		boolean contains(long cell) {
			int x = BlockPos.getX(cell);
			int y = BlockPos.getY(cell);
			int z = BlockPos.getZ(cell);
			return x >= minX && x <= maxX && z >= minZ && z <= maxZ && y >= minY && y <= maxY;
		}
	}

	/** What a surface must be to count, and what is going to be placed on it. */
	record Settings(int emission, boolean fullBlock, int threshold) {
	}

	/** Detached copy of the chunk sections around an area. {@code states} / {@code light}: [chunk][section]. */
	record Copy(Area area, int chunkX, int chunkZ, int chunksX, int chunksZ, int sectionY, int sections, boolean[] loaded,
		PalettedContainer<BlockState>[][] states, DataLayer[][] light) {
	}

	/**
	 * Dense block grid plus the result of the scan. {@code dark}: cells (see {@link BlockPos#asLong()}) of the surfaces at
	 * or below the threshold; {@code candidates}: grid indices where a source could be placed.
	 */
	record Snapshot(Settings settings, Area area, int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ,
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

		/** Grid index of a world cell, -1 when it is outside the grid. */
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

	/** Per block state answers, worked out once per scan. */
	private static final int FREE = 1;
	private static final int STURDY = 2;
	private static final int CENTER = 4;
	private static final int REPLACEABLE = 8;

	private LightScan() {
	}

	/** Client thread only. */
	@SuppressWarnings("unchecked")
	static Copy copy(ClientLevel world, Area area) {
		int chunkX = (area.minX() - PAD) >> 4;
		int chunkZ = (area.minZ() - PAD) >> 4;
		int chunksX = ((area.maxX() + PAD) >> 4) - chunkX + 1;
		int chunksZ = ((area.maxZ() + PAD) >> 4) - chunkZ + 1;
		int minY = world.getMinY();
		int maxY = minY + world.getHeight() - 1;
		int sectionY = Math.max(minY, area.minY() - PAD) >> 4;
		int sections = Math.max(0, (Math.min(maxY, area.maxY() + PAD) >> 4) - sectionY + 1);

		boolean[] loaded = new boolean[chunksX * chunksZ];
		PalettedContainer<BlockState>[][] states = new PalettedContainer[chunksX * chunksZ][sections];
		DataLayer[][] light = new DataLayer[chunksX * chunksZ][sections];
		LayerLightEventListener engine = world.getLightEngine().getLayerListener(LightLayer.BLOCK);
		for (int x = 0; x < chunksX; x++) {
			for (int z = 0; z < chunksZ; z++) {
				if (!world.hasChunk(chunkX + x, chunkZ + z)) {
					continue;
				}
				int at = x + chunksX * z;
				loaded[at] = true;
				LevelChunk chunk = world.getChunk(chunkX + x, chunkZ + z);
				for (int s = 0; s < sections; s++) {
					int index = world.getSectionIndexFromSectionY(sectionY + s);
					LevelChunkSection section = chunk.getSection(index);
					if (!section.hasOnlyAir()) {
						states[at][s] = section.getStates().copy();
					}
					DataLayer data = engine.getDataLayerData(SectionPos.of(chunkX + x, sectionY + s, chunkZ + z));
					if (data != null) {
						light[at][s] = data.copy();
					}
				}
			}
		}
		return new Copy(area, chunkX, chunkZ, chunksX, chunksZ, sectionY, sections, loaded, states, light);
	}

	/** Any thread. */
	static Snapshot scan(Copy copy, Settings settings) {
		Area area = copy.area();
		// Everything above the highest and below the lowest block is empty: leave it out of the grid.
		int lowest = Integer.MAX_VALUE;
		int highest = Integer.MIN_VALUE;
		for (PalettedContainer<BlockState>[] column : copy.states()) {
			for (int s = 0; s < column.length; s++) {
				if (column[s] != null) {
					lowest = Math.min(lowest, copy.sectionY() + s);
					highest = Math.max(highest, copy.sectionY() + s);
				}
			}
		}
		int originX = area.minX() - PAD;
		int originZ = area.minZ() - PAD;
		int sizeX = area.maxX() - area.minX() + 1 + 2 * PAD;
		int sizeZ = area.maxZ() - area.minZ() + 1 + 2 * PAD;
		int bottom = lowest == Integer.MAX_VALUE ? 0 : Math.max(area.minY() - PAD, lowest * 16);
		int top = lowest == Integer.MAX_VALUE ? -1 : Math.min(area.maxY() + PAD, highest * 16 + 15 + PAD);
		int sizeY = Math.max(0, top - bottom + 1);

		BlockState[] states = new BlockState[sizeX * sizeY * sizeZ];
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState wall = Blocks.BEDROCK.defaultBlockState();
		Arrays.fill(states, air);
		for (int x = 0; x < sizeX; x++) {
			for (int z = 0; z < sizeZ; z++) {
				int wx = originX + x;
				int wz = originZ + z;
				int at = (wx >> 4) - copy.chunkX() + copy.chunksX() * ((wz >> 4) - copy.chunkZ());
				for (int y = 0; y < sizeY; y++) {
					int index = x + sizeX * (z + sizeZ * y);
					if (!copy.loaded()[at]) {
						states[index] = wall;
						continue;
					}
					int wy = bottom + y;
					int s = (wy >> 4) - copy.sectionY();
					if (s >= 0 && s < copy.sections() && copy.states()[at][s] != null) {
						states[index] = copy.states()[at][s].get(wx & 15, wy & 15, wz & 15);
					}
				}
			}
		}

		Map<BlockState, Integer> known = new IdentityHashMap<>();
		long[] dark = new long[1024];
		int[] candidates = new int[1024];
		int darkCount = 0;
		int candidateCount = 0;
		int layer = sizeX * sizeZ;
		int first = Math.max(area.minY(), bottom + 1);
		int last = Math.min(area.maxY(), bottom + sizeY - 1);
		for (int wx = area.minX(); wx <= area.maxX(); wx++) {
			for (int wz = area.minZ(); wz <= area.maxZ(); wz++) {
				int at = (wx >> 4) - copy.chunkX() + copy.chunksX() * ((wz >> 4) - copy.chunkZ());
				if (!copy.loaded()[at]) {
					continue;
				}
				for (int wy = first; wy <= last; wy++) {
					int above = wx - originX + sizeX * (wz - originZ) + layer * (wy - bottom);
					BlockState floor = states[above - layer];
					if (floor.isAir()) {
						continue;
					}
					BlockState cell = states[above];
					int cellFlags = flags(known, cell);
					int floorFlags = flags(known, floor);
					if ((cellFlags & FREE) == 0 || (floorFlags & STURDY) == 0) {
						continue;
					}
					int s = (wy >> 4) - copy.sectionY();
					DataLayer data = s >= 0 && s < copy.sections() ? copy.light()[at][s] : null;
					int level = data == null ? 0 : data.get(wx & 15, wy & 15, wz & 15);
					if (level <= settings.threshold()) {
						if (darkCount == dark.length) {
							dark = Arrays.copyOf(dark, darkCount * 2);
						}
						dark[darkCount++] = BlockPos.asLong(wx, wy, wz);
					}
					if ((cellFlags & REPLACEABLE) != 0 && (settings.fullBlock() || (floorFlags & CENTER) != 0)) {
						if (candidateCount == candidates.length) {
							candidates = Arrays.copyOf(candidates, candidateCount * 2);
						}
						candidates[candidateCount++] = above;
					}
				}
			}
		}
		return new Snapshot(settings, area, originX, bottom, originZ, sizeX, sizeY, sizeZ, states, Arrays.copyOf(dark, darkCount),
			Arrays.copyOf(candidates, candidateCount));
	}

	private static int flags(Map<BlockState, Integer> known, BlockState state) {
		Integer cached = known.get(state);
		if (cached != null) {
			return cached;
		}
		EmptyBlockGetter none = EmptyBlockGetter.INSTANCE;
		int flags = 0;
		// Empty space a mob could stand in: no collision and no fluid.
		if (state.isAir() || state.getCollisionShape(none, BlockPos.ZERO).isEmpty() && state.getFluidState().isEmpty()) {
			flags |= FREE;
		}
		if (state.isFaceSturdy(none, BlockPos.ZERO, Direction.UP)) {
			flags |= STURDY;
		}
		if (state.isFaceSturdy(none, BlockPos.ZERO, Direction.UP, SupportType.CENTER)) {
			flags |= CENTER;
		}
		if (state.canBeReplaced() && state.getFluidState().isEmpty()) {
			flags |= REPLACEABLE;
		}
		known.put(state, flags);
		return flags;
	}
}
