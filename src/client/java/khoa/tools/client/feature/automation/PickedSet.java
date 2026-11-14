package khoa.tools.client.feature.automation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.setting.Setting;

/** A list chosen with the item picker ("item:minecraft:bread", "block:minecraft:stone") with a default used until the player edits it. */
final class PickedSet {
	private final String section;
	private final String key;
	private final List<String> defaults;
	private volatile List<String> keys = List.of();
	private volatile Set<Item> items = Set.of();
	private volatile Set<Block> blocks = Set.of();

	PickedSet(String section, String key, List<String> defaults) {
		this.section = section;
		this.key = key;
		this.defaults = defaults;
		reload();
	}

	void reload() {
		boolean saved = KhoaConfig.section(section).has(key);
		apply(saved ? KhoaConfig.getStringList(section, key) : defaults);
	}

	List<String> keys() {
		return keys;
	}

	void set(List<String> value) {
		KhoaConfig.putStringList(section, key, value);
		apply(value);
	}

	Set<Item> items() {
		return items;
	}

	Set<Block> blocks() {
		return blocks;
	}

	Setting setting(String label, String description, List<String> kinds) {
		return Setting.itemList(key, label, description, kinds, this::keys, this::set);
	}

	private void apply(List<String> value) {
		Set<Item> itemSet = new HashSet<>();
		Set<Block> blockSet = new HashSet<>();
		for (String entry : value) {
			int colon = entry.indexOf(':');
			if (colon < 0) {
				continue;
			}
			String kind = entry.substring(0, colon);
			Identifier id = Identifier.tryParse(entry.substring(colon + 1));
			if (id == null) {
				continue;
			}
			if (kind.equals("item")) {
				BuiltInRegistries.ITEM.getOptional(id).ifPresent(itemSet::add);
			} else if (kind.equals("block")) {
				BuiltInRegistries.BLOCK.getOptional(id).ifPresent(blockSet::add);
			}
		}
		keys = List.copyOf(value);
		items = itemSet;
		blocks = blockSet;
	}
}
