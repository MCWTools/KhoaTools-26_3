package khoa.tools.client.feature.inventorytweak;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import khoa.tools.KhoaTools;

/**
 * Extra saved hotbars, numbered 1..infinity, in their own file ({@code config/khoatools-hotbars.json}). The vanilla
 * {@code hotbar.nbt} (9 hotbars) is never read or written, so nothing is lost without the mod. Only hotbars that were
 * saved exist in the file; items are decoded one page at a time, when a page is shown.
 */
public final class SavedHotbarStore {
	private static final Map<Integer, JsonArray> RAW = new TreeMap<>();
	private static final Map<Integer, ItemStack[]> DECODED = new HashMap<>();
	private static boolean loaded;

	private SavedHotbarStore() {}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("khoatools-hotbars.json");
	}

	private static void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		Path f = file();
		if (!Files.exists(f)) {
			return;
		}
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
			JsonObject hotbars = root.getAsJsonObject("hotbars");
			if (hotbars != null) {
				for (Map.Entry<String, JsonElement> e : hotbars.entrySet()) {
					RAW.put(Integer.parseInt(e.getKey()), e.getValue().getAsJsonArray());
				}
			}
		} catch (Exception e) {
			KhoaTools.LOGGER.error("Could not read khoatools-hotbars.json", e);
		}
	}

	public static boolean has(int number) {
		ensureLoaded();
		return RAW.containsKey(number);
	}

	/** The 9 items of a hotbar (empty stacks if never saved). Decoded on first use. */
	public static ItemStack[] get(int number) {
		ensureLoaded();
		ItemStack[] cached = DECODED.get(number);
		if (cached != null) {
			return cached;
		}
		ItemStack[] items = new ItemStack[9];
		java.util.Arrays.fill(items, ItemStack.EMPTY);
		JsonArray raw = RAW.get(number);
		Minecraft mc = Minecraft.getInstance();
		if (raw != null && mc.level != null) {
			RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, mc.level.registryAccess());
			for (int i = 0; i < 9 && i < raw.size(); i++) {
				items[i] = ItemStack.OPTIONAL_CODEC.parse(ops, raw.get(i)).result().orElse(ItemStack.EMPTY);
			}
		}
		DECODED.put(number, items);
		return items;
	}

	/** Stores the items as hotbar {@code number}; all-empty hotbars are removed from the file. */
	public static void put(int number, ItemStack[] items) {
		ensureLoaded();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		boolean any = false;
		JsonArray arr = new JsonArray();
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, mc.level.registryAccess());
		ItemStack[] copy = new ItemStack[9];
		for (int i = 0; i < 9; i++) {
			ItemStack s = i >= items.length || items[i] == null ? ItemStack.EMPTY : items[i].copy();
			copy[i] = s;
			any |= !s.isEmpty();
			arr.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, s).result().orElseGet(JsonObject::new));
		}
		if (any) {
			RAW.put(number, arr);
			DECODED.put(number, copy);
		} else {
			RAW.remove(number);
			DECODED.remove(number);
		}
		write();
	}

	private static void write() {
		JsonObject hotbars = new JsonObject();
		for (Map.Entry<Integer, JsonArray> e : RAW.entrySet()) {
			hotbars.add(Integer.toString(e.getKey()), e.getValue());
		}
		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
		root.add("hotbars", hotbars);
		Path f = file();
		Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			new com.google.gson.GsonBuilder().create().toJson(root, w);
		} catch (IOException e) {
			KhoaTools.LOGGER.error("Could not write khoatools-hotbars.json", e);
			return;
		}
		try {
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ex) {
				KhoaTools.LOGGER.error("Could not replace khoatools-hotbars.json", ex);
			}
		}
	}

	/** The cache is per world (registry access differs); the file stays. */
	public static void dropCache() {
		DECODED.clear();
	}
}
