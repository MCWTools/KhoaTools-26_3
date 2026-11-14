package khoa.tools.client.feature.illegal.spawnerfinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Finds every (trial) spawner in the loaded chunks around you, throttled to one scan every 2 seconds. */
final class SpawnerScanner {
	/** type is null when the spawner has no mob set yet. */
	record Info(BlockPos pos, EntityType<?> type, String mobId, boolean trial, double distance) {
		/** Key as stored by the picker. */
		String pickKey() {
			return (trial ? "trial:" : "spawner:") + mobId;
		}
	}

	private static final long INTERVAL_MS = 2000;
	private static final int CHUNK_RADIUS = 10; // about 160 blocks

	private static volatile List<Info> found = List.of();
	private static long lastScan;

	private SpawnerScanner() {
	}

	static List<Info> found() {
		return found;
	}

	static void clear() {
		found = List.of();
		lastScan = 0;
	}

	static void scanIfNeeded(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastScan < INTERVAL_MS) {
			return;
		}
		lastScan = now;
		found = scan(mc.level, mc.player.blockPosition());
	}

	private static List<Info> scan(Level level, BlockPos player) {
		List<Info> out = new ArrayList<>();
		int cx = player.getX() >> 4;
		int cz = player.getZ() >> 4;
		for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
			for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
				if (!(level.getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false) instanceof LevelChunk chunk)) {
					continue; // not loaded
				}
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					boolean trial = be instanceof TrialSpawnerBlockEntity;
					if (!trial && !(be instanceof SpawnerBlockEntity)) {
						continue;
					}
					BlockPos pos = be.getBlockPos();
					EntityType<?> type = mobOf(be, level, pos);
					String id = type == null ? "unknown" : SpawnerMobs.id(type);
					out.add(new Info(pos.immutable(), type, id, trial, Math.sqrt(player.distSqr(pos))));
				}
			}
		}
		out.sort(Comparator.comparingDouble(Info::distance));
		return List.copyOf(out);
	}

	private static EntityType<?> mobOf(BlockEntity be, Level level, BlockPos pos) {
		try {
			Entity display = null;
			if (be instanceof SpawnerBlockEntity spawner) {
				display = spawner.getSpawner().getOrCreateDisplayEntity(level, pos);
			} else if (be instanceof TrialSpawnerBlockEntity trial) {
				TrialSpawner ts = trial.getTrialSpawner();
				display = ts.getStateData().getOrCreateDisplayEntity(ts, level, ts.getState());
			}
			return display == null ? null : display.getType();
		} catch (RuntimeException e) {
			return null; // spawner data not synced yet
		}
	}
}
