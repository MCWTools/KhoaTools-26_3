package khoa.tools.client.feature.fakeplayer;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import khoa.tools.KhoaTools;
import khoa.tools.client.feature.camera.CameraStore;

/** Fake players of every server + dimension, saved in config/khoatools-fakeplayers.json. */
public final class FakePlayerStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type TYPE = new TypeToken<LinkedHashMap<String, List<FakePlayerData>>>() { }.getType();
	private static final Map<String, List<FakePlayerData>> WORLDS = new LinkedHashMap<>();
	private static boolean loaded;

	private FakePlayerStore() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("khoatools-fakeplayers.json");
	}

	private static synchronized void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		Path file = file();
		if (!Files.exists(file)) return;
		try {
			Map<String, List<FakePlayerData>> read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), TYPE);
			if (read != null) WORLDS.putAll(read);
		} catch (Exception e) {
			KhoaTools.LOGGER.error("Could not read khoatools-fakeplayers.json", e);
		}
	}

	public static synchronized void save() {
		ensureLoaded();
		Map<String, List<FakePlayerData>> out = new LinkedHashMap<>();
		for (Map.Entry<String, List<FakePlayerData>> e : WORLDS.entrySet()) {
			if (!e.getValue().isEmpty()) out.put(e.getKey(), e.getValue());
		}
		try {
			Files.writeString(file(), GSON.toJson(out, TYPE), StandardCharsets.UTF_8);
		} catch (IOException e) {
			KhoaTools.LOGGER.error("Could not write khoatools-fakeplayers.json", e);
		}
	}

	/** Fake players of the current server + dimension (live list, empty when not in a world). */
	public static synchronized List<FakePlayerData> current(Minecraft mc) {
		ensureLoaded();
		String key = CameraStore.worldKey(mc);
		if (key == null) return new ArrayList<>();
		return WORLDS.computeIfAbsent(key, k -> new ArrayList<>());
	}

	public static synchronized FakePlayerData find(Minecraft mc, int id) {
		for (FakePlayerData d : current(mc)) {
			if (d.id == id) return d;
		}
		return null;
	}

	/** Adds a new entry with the next free id (unique inside this world). Not saved until {@link #save()}. */
	public static synchronized FakePlayerData create(Minecraft mc) {
		List<FakePlayerData> list = current(mc);
		int id = 1;
		for (FakePlayerData d : list) id = Math.max(id, d.id + 1);
		FakePlayerData data = new FakePlayerData();
		data.id = id;
		data.name = "NPC" + id;
		list.add(data);
		return data;
	}

	public static synchronized void delete(Minecraft mc, FakePlayerData data) {
		current(mc).remove(data);
		save();
	}
}
