package khoa.tools.client.gui.picker;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Block;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.setting.PickAdvanced;
import khoa.tools.setting.Setting;

/**
 * A saved item / block / enchanted book list (config/khoatools.json) that a feature can ask "is this one of them?".
 * An empty list matches nothing; features decide what empty means (usually "no filter") with {@link #isEmpty()}.
 * Use {@link #setting} for the config row that opens the picker.
 */
public final class PickFilter implements PickAdvanced.Store {
	private final String section;
	private final String key;
	private volatile Set<String> ids = Set.of();
	private volatile Set<String> books = Set.of();
	private volatile List<String> keys = List.of();
	/** Replaced (never changed) on every edit, so features can cache by identity. */
	private volatile Map<String, PickAdvanced> advanced = Map.of();

	public PickFilter(String section, String key) {
		this.section = section;
		this.key = key;
		reload();
	}

	public void reload() {
		advanced = readAdvanced();
		set(KhoaConfig.getStringList(section, key), false);
	}

	public List<String> get() {
		return keys;
	}

	public void set(List<String> value) {
		set(value, true);
	}

	private void set(List<String> value, boolean save) {
		Set<String> idSet = new HashSet<>();
		Set<String> bookSet = new HashSet<>();
		for (String entry : value) {
			int colon = entry.indexOf(':');
			String kind = colon < 0 ? PickData.ITEM : entry.substring(0, colon);
			String id = colon < 0 ? entry : entry.substring(colon + 1);
			(kind.equals(PickData.BOOK) ? bookSet : idSet).add(id);
		}
		ids = idSet;
		books = bookSet;
		keys = List.copyOf(value);
		if (save) {
			KhoaConfig.putStringList(section, key, value);
			if (advanced.keySet().stream().anyMatch(k -> !keys.contains(k))) { // a removed pick forgets its Advanced Setting
				Map<String, PickAdvanced> kept = new HashMap<>(advanced);
				kept.keySet().retainAll(keys);
				advanced = Map.copyOf(kept);
				writeAdvanced();
			}
		}
	}

	// ---- Advanced Setting per pick (stored next to the list as "<key>_advanced") ----

	@Override
	public PickAdvanced advanced(String entryKey) {
		return advanced.getOrDefault(entryKey, PickAdvanced.DEFAULT);
	}

	@Override
	public void setAdvanced(String entryKey, PickAdvanced value) {
		Map<String, PickAdvanced> next = new HashMap<>(advanced);
		if (value == null || value.isDefault()) {
			next.remove(entryKey);
		} else {
			next.put(entryKey, value);
		}
		advanced = Map.copyOf(next);
		writeAdvanced();
	}

	/** Identity changes whenever any Advanced Setting changes. */
	public Object advancedVersion() {
		return advanced;
	}

	private Map<String, PickAdvanced> readAdvanced() {
		Map<String, PickAdvanced> out = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : KhoaConfig.getObject(section, key + "_advanced").entrySet()) {
			try {
				JsonObject o = e.getValue().getAsJsonObject();
				PickAdvanced value = new PickAdvanced(readInt(o, "min_y"), readInt(o, "max_y"), readInt(o, "rgb"));
				if (!value.isDefault()) {
					out.put(e.getKey(), value);
				}
			} catch (RuntimeException ignored) {
				// a hand edited entry that cannot be read stays at the defaults
			}
		}
		return Map.copyOf(out);
	}

	private static Integer readInt(JsonObject o, String name) {
		return o.has(name) ? Integer.valueOf(o.get(name).getAsInt()) : null;
	}

	private void writeAdvanced() {
		JsonObject all = new JsonObject();
		advanced.forEach((entryKey, value) -> {
			JsonObject o = new JsonObject();
			if (value.minY() != null) {
				o.addProperty("min_y", value.minY());
			}
			if (value.maxY() != null) {
				o.addProperty("max_y", value.maxY());
			}
			if (value.rgb() != null) {
				o.addProperty("rgb", value.rgb());
			}
			all.add(entryKey, o);
		});
		KhoaConfig.putObject(section, key + "_advanced", all);
	}

	public boolean isEmpty() {
		return keys.isEmpty();
	}

	public boolean matchesItem(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		if (ids.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
			return true;
		}
		if (!books.isEmpty() && stack.is(Items.ENCHANTED_BOOK)) {
			ItemEnchantments stored = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
			return stored.keySet().stream().anyMatch(h -> h.unwrapKey().map(k -> books.contains(k.identifier().toString())).orElse(false));
		}
		return false;
	}

	public boolean matchesBlock(Block block) {
		return ids.contains(BuiltInRegistries.BLOCK.getKey(block).toString());
	}

	/** Picked, and {@code y} is inside that pick's Y level range. */
	public boolean matchesBlock(Block block, int y) {
		String id = BuiltInRegistries.BLOCK.getKey(block).toString();
		return ids.contains(id) && advanced(PickData.key(PickData.BLOCK, id)).allowsY(y);
	}

	/** The config row. {@code kinds}: {@code List.of("item")} (items + enchanted books) or {@code List.of("block")}. */
	public Setting setting(String label, String description, List<String> kinds) {
		return Setting.itemList(key, label, description, kinds, this::get, this::set);
	}
}
