package khoa.tools.client.feature.invprofile;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The kind-of-item names a profile slot can ask for ("the best Sword"), with the rule that ranks the candidates and the preferences
 * (enchantments the player would like to have). A preference only decides between items of the same kind: an item that has the preferred
 * enchantments always wins over one that has not, and the usual ranking (material, enchantments, durability) decides inside each group.
 * Option values are stored as the index of the choice; 0 = "no preference".
 */
public enum ProfileType {
	SWORD("Sword", Items.IRON_SWORD),
	SPEAR("Spear", Items.IRON_SPEAR),
	MACE("Mace", Items.MACE),
	TRIDENT("Trident", Items.TRIDENT),
	PICKAXE("Pickaxe", Items.IRON_PICKAXE),
	AXE("Axe", Items.IRON_AXE),
	SHOVEL("Shovel", Items.IRON_SHOVEL),
	HOE("Hoe", Items.IRON_HOE),
	BOW("Bow", Items.BOW),
	CROSSBOW("Crossbow", Items.CROSSBOW),
	SHIELD("Shield", Items.SHIELD),
	TOTEM("Totem", Items.TOTEM_OF_UNDYING),
	FIREWORK("Firework Rocket", Items.FIREWORK_ROCKET),
	FOOD("Food", Items.COOKED_BEEF),
	PEARL("Ender Pearl", Items.ENDER_PEARL),
	WIND_CHARGE("Wind Charge", Items.WIND_CHARGE),
	HELMET("Helmet", Items.IRON_HELMET),
	CHESTPLATE("Chestplate", Items.IRON_CHESTPLATE),
	ELYTRA("Elytra", Items.ELYTRA),
	LEGGINGS("Leggings", Items.IRON_LEGGINGS),
	BOOTS("Boots", Items.IRON_BOOTS);

	/** One dropdown of the options screen. */
	public record Option(String key, String label, List<String> values) {
	}

	private static final List<String> OFF_PREFER = List.of("Off", "Prefer");
	private static final List<String> NONE_123 = List.of("None", "I", "II", "III");

	private final String label;
	private final Item icon;

	ProfileType(String label, Item icon) {
		this.label = label;
		this.icon = icon;
	}

	public String label() {
		return label;
	}

	public ItemStack iconStack() {
		return new ItemStack(icon);
	}

	public static ProfileType byName(String name) {
		for (ProfileType t : values()) {
			if (t.name().equalsIgnoreCase(name)) {
				return t;
			}
		}
		return null;
	}

	/** The dropdowns shown after choosing this kind. */
	public List<Option> options() {
		return switch (this) {
			case SWORD -> List.of(
				new Option("kb", "Knockback", List.of("None", "I", "II")),
				new Option("dmg", "Damage enchantment", List.of("Any", "Sharpness", "Smite", "Bane of Arthropods")),
				new Option("fire", "Fire Aspect", OFF_PREFER));
			case MACE -> List.of(
				new Option("dens", "Breach or Density", List.of("Any", "Breach", "Density")),
				new Option("wind", "Wind Burst", NONE_123),
				new Option("fire", "Fire Aspect", OFF_PREFER));
			case SPEAR -> List.of(
				new Option("lunge", "Lunge", NONE_123),
				new Option("sharp", "Sharpness", OFF_PREFER),
				new Option("fire", "Fire Aspect", OFF_PREFER),
				new Option("kb", "Knockback", OFF_PREFER));
			case AXE -> List.of(
				new Option("sharp", "Sharpness", OFF_PREFER),
				new Option("silk", "Silk Touch", OFF_PREFER));
			case PICKAXE -> List.of(new Option("silk", "Silk Touch", OFF_PREFER));
			case FIREWORK -> List.of(new Option("flight", "Flight duration", List.of("1", "2", "3")));
			case TRIDENT -> List.of(new Option("mode", "Enchantment (only one of them)",
				List.of("Any", "Riptide I", "Riptide II", "Riptide III", "Loyalty", "Channeling", "Impaling")));
			default -> List.of();
		};
	}

	public boolean matches(ItemStack s, Map<String, Integer> prefs) {
		boolean kind = switch (this) {
			case SWORD -> s.is(ItemTags.SWORDS);
			case SPEAR -> s.is(ItemTags.SPEARS);
			case MACE -> s.is(Items.MACE);
			case TRIDENT -> s.is(Items.TRIDENT);
			case PICKAXE -> s.is(ItemTags.PICKAXES);
			case AXE -> s.is(ItemTags.AXES);
			case SHOVEL -> s.is(ItemTags.SHOVELS);
			case HOE -> s.is(ItemTags.HOES);
			case BOW -> s.is(Items.BOW);
			case CROSSBOW -> s.is(Items.CROSSBOW);
			case SHIELD -> s.is(Items.SHIELD);
			case TOTEM -> s.is(Items.TOTEM_OF_UNDYING);
			case FIREWORK -> s.is(Items.FIREWORK_ROCKET);
			case FOOD -> s.get(DataComponents.FOOD) != null && !s.is(Items.GOLDEN_APPLE) && !s.is(Items.ENCHANTED_GOLDEN_APPLE);
			case PEARL -> s.is(Items.ENDER_PEARL);
			case WIND_CHARGE -> s.is(Items.WIND_CHARGE);
			case HELMET -> s.is(ItemTags.HEAD_ARMOR);
			case CHESTPLATE -> s.is(ItemTags.CHEST_ARMOR);
			case ELYTRA -> s.is(Items.ELYTRA);
			case LEGGINGS -> s.is(ItemTags.LEG_ARMOR);
			case BOOTS -> s.is(ItemTags.FOOT_ARMOR);
		};
		if (kind && this == FIREWORK) { // the flight duration is a requirement, not a preference
			Fireworks fireworks = s.get(DataComponents.FIREWORKS);
			int flight = fireworks == null ? 1 : fireworks.flightDuration();
			return flight == prefs.getOrDefault("flight", 0) + 1;
		}
		return kind;
	}

	/** Higher = better. */
	public double score(ItemStack s, Map<String, Integer> prefs) {
		if (this == FOOD) { // what it fills: saturation first (golden carrot is the best), then hunger points, then the bigger stack
			FoodProperties food = s.get(DataComponents.FOOD);
			return food == null ? 0 : food.saturation() * 1000 + food.nutrition() * 10 + Math.min(s.getCount(), 9) * 0.01;
		}
		if (this == TOTEM || this == FIREWORK || this == PEARL || this == WIND_CHARGE) {
			return s.getCount(); // no quality: take the biggest stack
		}
		double frac = s.getMaxDamage() > 0 ? 1.0 - (double) s.getDamageValue() / s.getMaxDamage() : 1.0;
		double enchant = enchantScore(s);
		double bonus = 10000.0 * preferencesMet(s, prefs);
		Block sample = switch (this) {
			case PICKAXE -> Blocks.STONE;
			case AXE -> Blocks.OAK_LOG;
			case SHOVEL -> Blocks.DIRT;
			case HOE -> Blocks.HAY_BLOCK;
			default -> null;
		};
		if (sample != null) {
			double speed = s.getDestroySpeed(sample.defaultBlockState());
			if (path(s).startsWith("golden_")) {
				speed = Math.min(speed, 5.0); // gold digs fast but is the weakest material: do not let it win
			}
			return bonus + speed * 10 + enchant + frac;
		}
		return bonus + material(s) * 100 + enchant + frac;
	}

	/** How many of the chosen preferences this item satisfies. */
	private int preferencesMet(ItemStack s, Map<String, Integer> prefs) {
		if (prefs.isEmpty()) {
			return 0;
		}
		Map<String, Integer> lv = levels(s);
		int met = 0;
		switch (this) {
			case SWORD -> {
				met += exactLevel(lv, "knockback", prefs.getOrDefault("kb", 0));
				met += oneOf(lv, prefs.getOrDefault("dmg", 0), "sharpness", "smite", "bane_of_arthropods");
				met += wants(lv, "fire_aspect", prefs.getOrDefault("fire", 0));
			}
			case MACE -> {
				met += oneOf(lv, prefs.getOrDefault("dens", 0), "breach", "density");
				met += exactLevel(lv, "wind_burst", prefs.getOrDefault("wind", 0));
				met += wants(lv, "fire_aspect", prefs.getOrDefault("fire", 0));
			}
			case SPEAR -> {
				met += exactLevel(lv, "lunge", prefs.getOrDefault("lunge", 0));
				met += wants(lv, "sharpness", prefs.getOrDefault("sharp", 0));
				met += wants(lv, "fire_aspect", prefs.getOrDefault("fire", 0));
				met += wants(lv, "knockback", prefs.getOrDefault("kb", 0));
			}
			case AXE -> {
				met += wants(lv, "sharpness", prefs.getOrDefault("sharp", 0));
				met += wants(lv, "silk_touch", prefs.getOrDefault("silk", 0));
			}
			case PICKAXE -> met += wants(lv, "silk_touch", prefs.getOrDefault("silk", 0));
			case TRIDENT -> {
				int mode = prefs.getOrDefault("mode", 0);
				if (mode >= 1 && mode <= 3) {
					met += exactLevel(lv, "riptide", mode);
				} else if (mode == 4) {
					met += wants(lv, "loyalty", 1);
				} else if (mode == 5) {
					met += wants(lv, "channeling", 1);
				} else if (mode == 6) {
					met += wants(lv, "impaling", 1);
				}
			}
			default -> {
			}
		}
		return met;
	}

	/** 1 when the preference is on and the item has the enchantment. */
	private static int wants(Map<String, Integer> lv, String name, int wanted) {
		return wanted > 0 && lv.getOrDefault(name, 0) > 0 ? 1 : 0;
	}

	private static int exactLevel(Map<String, Integer> lv, String name, int level) {
		return level > 0 && lv.getOrDefault(name, 0) == level ? 1 : 0;
	}

	/** {@code choice} 1.. picks one of the names (0 = no preference). */
	private static int oneOf(Map<String, Integer> lv, int choice, String... names) {
		return choice >= 1 && choice <= names.length && lv.getOrDefault(names[choice - 1], 0) > 0 ? 1 : 0;
	}

	private static Map<String, Integer> levels(ItemStack s) {
		Map<String, Integer> out = new HashMap<>();
		ItemEnchantments enchantments = s.get(DataComponents.ENCHANTMENTS);
		if (enchantments != null) {
			for (var entry : enchantments.entrySet()) {
				Holder<Enchantment> holder = entry.getKey();
				holder.unwrapKey().ifPresent(k -> out.put(k.identifier().getPath().toLowerCase(Locale.ROOT), entry.getIntValue()));
			}
		}
		return out;
	}

	private static String path(ItemStack s) {
		return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
	}

	private static int material(ItemStack s) {
		String p = path(s);
		if (p.startsWith("netherite")) {
			return 8;
		}
		if (p.startsWith("diamond")) {
			return 7;
		}
		if (p.startsWith("iron")) {
			return 5;
		}
		if (p.startsWith("copper") || p.startsWith("chainmail")) {
			return 4;
		}
		if (p.startsWith("stone") || p.startsWith("golden") || p.startsWith("turtle")) {
			return 3;
		}
		if (p.startsWith("leather") || p.startsWith("wooden")) {
			return 2;
		}
		return 1;
	}

	private static double enchantScore(ItemStack s) {
		double total = 0;
		for (var entry : levels(s).entrySet()) {
			total += weight(entry.getKey()) * entry.getValue();
		}
		return total;
	}

	private static double weight(String name) {
		return switch (name) {
			case "sharpness", "power", "density", "breach" -> 5;
			case "efficiency" -> 6;
			case "protection" -> 4;
			case "mending", "infinity" -> 6;
			case "fire_aspect", "flame", "fortune", "silk_touch", "quick_charge", "lunge", "wind_burst", "riptide", "impaling" -> 3;
			case "multishot" -> 4;
			case "unbreaking", "smite", "looting", "piercing", "fire_protection", "blast_protection", "projectile_protection", "feather_falling",
				"loyalty", "channeling" -> 2;
			case "binding_curse" -> -3;
			case "vanishing_curse" -> -1;
			default -> 1;
		};
	}
}
