package khoa.tools.client.feature.invprofile;

import java.util.UUID;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A saved layout. 41 entries: 0..3 armor (helmet to boots), 4 offhand, 5..13 hotbar, 14..40 main inventory (top row first).
 * An entry is "" (nothing: that slot is left alone), "type:SWORD" (the best item of that kind) or "item:minecraft:iron_pickaxe" (that item).
 */
public final class InvProfile {
	public static final int SIZE = 41;
	public static final int OFFHAND = 4;

	public String id = UUID.randomUUID().toString();
	public String name = "Profile";
	public String[] slots = new String[SIZE];

	public InvProfile() {
		java.util.Arrays.fill(slots, "");
	}

	public void normalize() {
		if (slots == null || slots.length != SIZE) {
			String[] fixed = new String[SIZE];
			java.util.Arrays.fill(fixed, "");
			if (slots != null) {
				System.arraycopy(slots, 0, fixed, 0, Math.min(slots.length, SIZE));
			}
			slots = fixed;
		}
		for (int i = 0; i < SIZE; i++) {
			if (slots[i] == null) {
				slots[i] = "";
			}
		}
		if (name == null || name.isBlank()) {
			name = "Profile";
		}
	}

	public int configured() {
		int n = 0;
		for (String s : slots) {
			if (!s.isEmpty()) {
				n++;
			}
		}
		return n;
	}

	/** Slot index of the profile -> slot number in the player's inventory menu (5..8 armor, 9..35 main, 36..44 hotbar, 45 offhand). */
	public static int menuSlot(int index) {
		if (index < 4) {
			return 5 + index;
		}
		if (index == OFFHAND) {
			return 45;
		}
		if (index < 14) {
			return 36 + (index - 5);
		}
		return 9 + (index - 14);
	}

	/** What a slot entry asks for: a kind of item with preferences (option index per key), or one exact item. */
	public record Request(ProfileType type, Item item, java.util.Map<String, Integer> prefs) {
		public boolean matches(ItemStack stack) {
			return type != null ? type.matches(stack, prefs) : stack.getItem() == item;
		}

		/** Higher = better; an exact item prefers the biggest stack. */
		public double score(ItemStack stack) {
			return type != null ? type.score(stack, prefs) : stack.getCount();
		}

		public String label() {
			return type != null ? type.label() : item.getName(new ItemStack(item)).getString();
		}

		/** "Sword (Knockback: II, Fire Aspect: Prefer)" */
		public String describe() {
			if (type == null) {
				return label();
			}
			java.util.List<String> parts = new java.util.ArrayList<>();
			for (ProfileType.Option option : type.options()) {
				int value = prefs.getOrDefault(option.key(), 0);
				if (value > 0 || option.key().equals("flight")) {
					parts.add(option.label() + ": " + option.values().get(Math.min(value, option.values().size() - 1)));
				}
			}
			return parts.isEmpty() ? type.label() : type.label() + " (" + String.join(", ", parts) + ")";
		}
	}

	/** "type:SWORD|kb=1|fire=1" (a preference at 0 is not written). */
	public static String typeEntry(ProfileType type, java.util.Map<String, Integer> prefs) {
		StringBuilder sb = new StringBuilder("type:").append(type.name());
		for (ProfileType.Option option : type.options()) {
			int value = prefs.getOrDefault(option.key(), 0);
			if (value > 0) {
				sb.append('|').append(option.key()).append('=').append(value);
			}
		}
		return sb.toString();
	}

	/** The kind of a "type:" entry, or null. */
	public static ProfileType typeOf(String entry) {
		if (entry == null || !entry.startsWith("type:")) {
			return null;
		}
		int bar = entry.indexOf('|');
		return ProfileType.byName(entry.substring(5, bar < 0 ? entry.length() : bar));
	}

	public static java.util.Map<String, Integer> prefsOf(String entry) {
		java.util.Map<String, Integer> prefs = new java.util.HashMap<>();
		if (entry != null) {
			String[] parts = entry.split("[|]");
			for (int i = 1; i < parts.length; i++) {
				int eq = parts[i].indexOf('=');
				if (eq > 0) {
					try {
						prefs.put(parts[i].substring(0, eq), Integer.parseInt(parts[i].substring(eq + 1)));
					} catch (NumberFormatException ignored) {
						// a damaged value counts as "no preference"
					}
				}
			}
		}
		return prefs;
	}

	public static Request parse(String entry) {
		if (entry == null || entry.isEmpty()) {
			return null;
		}
		if (entry.startsWith("type:")) {
			ProfileType type = typeOf(entry);
			return type == null ? null : new Request(type, null, prefsOf(entry));
		}
		if (entry.startsWith("item:")) {
			Identifier id = Identifier.tryParse(entry.substring(5));
			if (id == null) {
				return null;
			}
			return BuiltInRegistries.ITEM.getOptional(id).map(item -> new Request(null, item, java.util.Map.of())).orElse(null);
		}
		return null;
	}
}
