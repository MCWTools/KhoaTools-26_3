package khoa.tools.client.gui.picker;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Block;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.setting.Setting;

/**
 * A saved item / block / enchanted book list (config/khoatools.json) that a feature can ask "is this one of them?".
 * An empty list matches nothing; features decide what empty means (usually "no filter") with {@link #isEmpty()}.
 * Use {@link #setting} for the config row that opens the picker.
 */
public final class PickFilter {
	private final String section;
	private final String key;
	private volatile Set<String> ids = Set.of();
	private volatile Set<String> books = Set.of();
	private volatile List<String> keys = List.of();

	public PickFilter(String section, String key) {
		this.section = section;
		this.key = key;
		reload();
	}

	public void reload() {
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
		}
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

	/** The config row. {@code kinds}: {@code List.of("item")} (items + enchanted books) or {@code List.of("block")}. */
	public Setting setting(String label, String description, List<String> kinds) {
		return Setting.itemList(key, label, description, kinds, this::get, this::set);
	}
}
