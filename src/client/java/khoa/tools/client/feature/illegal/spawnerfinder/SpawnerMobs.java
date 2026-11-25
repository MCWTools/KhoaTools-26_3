package khoa.tools.client.feature.illegal.spawnerfinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner;

/** Which mobs a spawner can hold: the vanilla ones, or every mob in the registry (custom spawners on servers), plus their colours. */
public final class SpawnerMobs {
	/** Mobs that vanilla generates spawners / trial spawners for. */
	private static final List<EntityType<?>> VANILLA = List.of(
		EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.SPIDER, EntityTypes.CAVE_SPIDER, EntityTypes.MAGMA_CUBE, EntityTypes.BLAZE,
		EntityTypes.SILVERFISH, EntityTypes.BREEZE, EntityTypes.BOGGED, EntityTypes.STRAY, EntityTypes.HUSK, EntityTypes.SLIME);

	private static List<EntityType<?>> all;

	private SpawnerMobs() {
	}

	public static List<EntityType<?>> vanilla() {
		return VANILLA;
	}

	public static boolean isVanilla(EntityType<?> type) {
		return VANILLA.contains(type);
	}

	/** Every mob in the registry (anything with a spawn egg, or not in the MISC category), sorted by display name. */
	public static synchronized List<EntityType<?>> all() {
		if (all == null) {
			List<EntityType<?>> list = new ArrayList<>();
			for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
				if (type == EntityTypes.PLAYER) {
					continue;
				}
				if (type.getCategory() != MobCategory.MISC || SpawnEggItem.byId(type).isPresent()) {
					list.add(type);
				}
			}
			list.sort(Comparator.comparing(SpawnerMobs::name, String.CASE_INSENSITIVE_ORDER));
			all = list;
		}
		return all;
	}

	/**
	 * The mob inside a (trial) spawner as far as the client knows it, or null. A trial spawner only has a spinning mob in some states,
	 * so its synced spawn data is read first.
	 */
	public static EntityType<?> mobOf(BlockEntity be, Level level, BlockPos pos) {
		try {
			Entity display = null;
			if (be instanceof SpawnerBlockEntity spawner) {
				display = spawner.getSpawner().getOrCreateDisplayEntity(level, pos);
			} else if (be instanceof TrialSpawnerBlockEntity trial) {
				TrialSpawner ts = trial.getTrialSpawner();
				String synced = ts.getStateData().getUpdateTag(ts.getState())
					.getCompoundOrEmpty("spawn_data").getCompoundOrEmpty("entity").getStringOr("id", "");
				EntityType<?> type = synced.isEmpty() ? null : byId(synced);
				if (type != null) {
					return type;
				}
				display = ts.getStateData().getOrCreateDisplayEntity(ts, level, ts.getState());
			}
			return display == null ? null : display.getType();
		} catch (RuntimeException e) {
			return null; // spawner data not synced yet
		}
	}

	public static String id(EntityType<?> type) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
	}

	public static EntityType<?> byId(String id) {
		Identifier identifier = Identifier.tryParse(id);
		if (identifier == null) {
			return null;
		}
		return BuiltInRegistries.ENTITY_TYPE.getOptional(identifier).orElse(null);
	}

	public static String name(EntityType<?> type) {
		return type.getDescription().getString();
	}

	/** Colour of the square in the middle of the overlay / picker icon (ARGB). Known mobs keep SpawnerFinder's colours, others get a stable colour from their id. */
	public static int color(EntityType<?> type) {
		if (type == EntityTypes.SKELETON) return 0xFFFFFFFF;
		if (type == EntityTypes.ZOMBIE) return 0xFF00FF00;
		if (type == EntityTypes.SPIDER) return 0xFFFF0000;
		if (type == EntityTypes.CAVE_SPIDER) return 0xFFAA00FF;
		if (type == EntityTypes.MAGMA_CUBE) return 0xFFFF8000;
		if (type == EntityTypes.BLAZE) return 0xFFFFFF00;
		if (type == EntityTypes.SILVERFISH) return 0xFF808080;
		if (type == EntityTypes.BREEZE) return 0xFFC0E0FF;
		if (type == EntityTypes.BOGGED) return 0xFF5C714B;
		if (type == EntityTypes.STRAY) return 0xFFA0C0D0;
		if (type == EntityTypes.HUSK) return 0xFFC2B280;
		if (type == EntityTypes.SLIME) return 0xFF00FF80;
		return colorOf(id(type));
	}

	public static int colorOf(String id) {
		int h = id.toLowerCase(Locale.ROOT).hashCode();
		float hue = ((h & 0x7FFFFFFF) % 360) / 360.0F;
		float sat = 0.55F + ((h >>> 9) & 0x3F) / 255.0F;
		return 0xFF000000 | java.awt.Color.HSBtoRGB(hue, Math.min(1.0F, sat), 1.0F);
	}
}
