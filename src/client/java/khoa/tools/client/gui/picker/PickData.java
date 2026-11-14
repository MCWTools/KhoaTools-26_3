package khoa.tools.client.gui.picker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.level.block.Block;

import khoa.tools.client.feature.illegal.spawnerfinder.SpawnerMobs;

/**
 * Everything the picker needs to know about one pickable thing. A stored value is "kind:namespace:id" where kind is
 * {@code item}, {@code block} or {@code book} (enchanted book of that enchantment at its max level).
 */
public final class PickData {
	public static final String ITEM = "item";
	public static final String BLOCK = "block";
	public static final String BOOK = "book";
	/** Spawner pickers: a mob's normal spawner / its trial spawner. Stored as "spawner:minecraft:zombie" / "trial:minecraft:zombie". */
	public static final String SPAWNER = "spawner";
	public static final String TRIAL = "trial";
	/** Entity picker (Mob Finder): any entity type, players and special entities included. Stored as "entity:minecraft:zombie". */
	public static final String ENTITY = "entity";

	private PickData() {
	}

	/** One card. {@code search} is lower case name + id. */
	public record Entry(String key, String kind, String id, ItemStack stack, String name, String search, int swatch) {
		/** Cards without an item icon (spawners, entities) are drawn as a coloured tile. */
		public boolean hasSwatch() {
			return swatch != 0;
		}

		public boolean isSpawner() {
			return SPAWNER.equals(kind) || TRIAL.equals(kind);
		}

		public List<Component> tooltip() {
			String kindName = switch (kind) {
				case BLOCK -> "Block";
				case BOOK -> "Enchanted book";
				case SPAWNER -> "Spawner";
				case TRIAL -> "Trial spawner";
				case ENTITY -> "Entity";
				default -> "Item";
			};
			return List.of(Component.literal(name), Component.literal(id).withStyle(ChatFormatting.GRAY),
				Component.literal(kindName).withStyle(ChatFormatting.DARK_GRAY));
		}
	}

	public static String label(String kind) {
		return switch (kind) {
			case BLOCK -> "Blocks";
			case BOOK -> "Enchanted Books";
			default -> "Items";
		};
	}

	public static String key(String kind, String id) {
		return kind + ":" + id;
	}

	/**
	 * What a config row asks for: {@link #ITEM} = items AND enchanted books (a book is an item), {@link #BLOCK} = blocks only.
	 * Returns every pickable entry for that mode.
	 */
	public static List<Entry> catalog(String mode) {
		if (BLOCK.equals(mode)) {
			return list(BLOCK);
		}
		List<Entry> out = list(ITEM);
		out.addAll(list(BOOK));
		return out;
	}

	public static String modeLabel(String mode) {
		return ENTITY.equals(mode) ? "Entities" : SPAWNER.equals(mode) ? "Spawners" : BLOCK.equals(mode) ? "Blocks" : "Items & Enchanted Books";
	}

	/** Spawner picker list: every mob's normal spawner and trial spawner. {@code vanillaOnly} = only the mobs vanilla generates spawners for. Needs no world. */
	public static List<Entry> spawnerCatalog(boolean vanillaOnly) {
		List<Entry> out = new ArrayList<>();
		for (EntityType<?> type : vanillaOnly ? SpawnerMobs.vanilla() : SpawnerMobs.all()) {
			out.add(spawnerEntry(SPAWNER, type));
		}
		for (EntityType<?> type : vanillaOnly ? SpawnerMobs.vanilla() : SpawnerMobs.all()) {
			out.add(spawnerEntry(TRIAL, type));
		}
		return out;
	}

	/** Picker list for entities: every registered entity type (players, items, armor stands... included). Needs no world. */
	public static List<Entry> entityCatalog() {
		List<Entry> out = new ArrayList<>();
		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			out.add(entityEntry(SpawnerMobs.id(type), SpawnerMobs.name(type)));
		}
		out.sort(java.util.Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
		return out;
	}

	private static Entry entityEntry(String id, String name) {
		return new Entry(key(ENTITY, id), ENTITY, id, ItemStack.EMPTY, name, (name + " " + id).toLowerCase(Locale.ROOT), SpawnerMobs.colorOf(id));
	}

	private static Entry spawnerEntry(String kind, EntityType<?> type) {
		String id = SpawnerMobs.id(type);
		return spawnerEntry(kind, id, SpawnerMobs.name(type), SpawnerMobs.color(type));
	}

	private static Entry spawnerEntry(String kind, String id, String mobName, int color) {
		String name = mobName + (TRIAL.equals(kind) ? " Trial Spawner" : " Spawner");
		return new Entry(key(kind, id), kind, id, ItemStack.EMPTY, name, (name + " " + id).toLowerCase(Locale.ROOT), color);
	}

	/** Item components are only bound once a world is joined, so nothing can be listed (or drawn) before that. */
	public static boolean available() {
		return Minecraft.getInstance().level != null;
	}

	/** Every entry of one kind, in registry order. Needs a world (item components and enchantments are bound on join). */
	public static List<Entry> list(String kind) {
		List<Entry> out = new ArrayList<>();
		if (!available()) {
			return out;
		}
		switch (kind) {
			case BLOCK -> {
				for (Block block : BuiltInRegistries.BLOCK) {
					if (block.defaultBlockState().isAir()) {
						continue;
					}
					Item item = block.asItem();
					ItemStack icon = new ItemStack(item == Items.AIR ? Items.BARRIER : item);
					String id = BuiltInRegistries.BLOCK.getKey(block).toString();
					out.add(entry(BLOCK, id, icon, block.getName().getString()));
				}
			}
			case BOOK -> {
				Registry<Enchantment> registry = enchantments();
				if (registry != null) {
					registry.listElements().forEach(holder -> out.add(book(registry, holder)));
				}
			}
			default -> {
				for (Item item : BuiltInRegistries.ITEM) {
					if (item == Items.AIR) {
						continue;
					}
					ItemStack stack = new ItemStack(item);
					out.add(entry(ITEM, BuiltInRegistries.ITEM.getKey(item).toString(), stack, stack.getHoverName().getString()));
				}
			}
		}
		return out;
	}

	/** Turns a stored value back into a card. Unknown ids (removed mod, no world yet) still get a card so they can be removed. */
	public static Entry resolve(String key) {
		int sc = key.indexOf(':');
		String sk = sc < 0 ? "" : key.substring(0, sc);
		if (sk.equals(ENTITY)) {
			String eid = key.substring(sc + 1);
			EntityType<?> type = SpawnerMobs.byId(eid);
			return entityEntry(eid, type != null ? SpawnerMobs.name(type) : eid);
		}
		if (sk.equals(SPAWNER) || sk.equals(TRIAL)) {
			String sid = key.substring(sc + 1);
			EntityType<?> type = SpawnerMobs.byId(sid);
			return type != null ? spawnerEntry(sk, type) : spawnerEntry(sk, sid, sid, SpawnerMobs.colorOf(sid));
		}
		if (!available()) {
			int c = key.indexOf(':');
			return entry(c < 0 ? ITEM : key.substring(0, c), c < 0 ? key : key.substring(c + 1), ItemStack.EMPTY, c < 0 ? key : key.substring(c + 1));
		}
		int colon = key.indexOf(':');
		String kind = colon < 0 ? ITEM : key.substring(0, colon);
		String id = colon < 0 ? key : key.substring(colon + 1);
		Identifier identifier = Identifier.tryParse(id);
		if (identifier != null) {
			switch (kind) {
				case BLOCK -> {
					Block block = BuiltInRegistries.BLOCK.getValue(identifier);
					if (BuiltInRegistries.BLOCK.getKey(block).equals(identifier)) {
						Item item = block.asItem();
						return entry(BLOCK, id, new ItemStack(item == Items.AIR ? Items.BARRIER : item), block.getName().getString());
					}
				}
				case BOOK -> {
					Registry<Enchantment> registry = enchantments();
					if (registry != null) {
						Enchantment enchantment = registry.getValue(identifier);
						if (enchantment != null) {
							return book(registry, registry.wrapAsHolder(enchantment));
						}
					}
				}
				default -> {
					Item item = BuiltInRegistries.ITEM.getValue(identifier);
					if (item != Items.AIR) {
						ItemStack stack = new ItemStack(item);
						return entry(ITEM, id, stack, stack.getHoverName().getString());
					}
				}
			}
		}
		return entry(kind, id, new ItemStack(kind.equals(BOOK) ? Items.ENCHANTED_BOOK : Items.BARRIER), id);
	}

	private static Registry<Enchantment> enchantments() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? null : mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
	}

	private static Entry book(Registry<Enchantment> registry, Holder<Enchantment> holder) {
		Enchantment enchantment = holder.value();
		ItemStack stack = EnchantmentHelper.createBook(new EnchantmentInstance(holder, enchantment.getMaxLevel()));
		String id = registry.getKey(enchantment).toString();
		return entry(BOOK, id, stack, enchantment.description().getString() + " (book)");
	}

	private static Entry entry(String kind, String id, ItemStack stack, String name) {
		return new Entry(key(kind, id), kind, id, stack, name, (name + " " + id).toLowerCase(Locale.ROOT), 0);
	}
}
