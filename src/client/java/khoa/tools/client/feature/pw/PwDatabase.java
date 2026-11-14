package khoa.tools.client.feature.pw;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;

import khoa.tools.KhoaTools;

/** Saved results of the /pw scan, per server: config/khoatools-pw.json. */
public final class PwDatabase {
	/** Teleported and the position was recorded. */
	public static final String OK = "ok";
	/** The command did not move the player. */
	public static final String NO_TP = "notp";
	/** A GUI opened (payment...), it was closed and no coordinate was recorded. Never tried again. */
	public static final String GUI = "gui";
	/** The player died before the coordinate was saved. Never tried again. */
	public static final String DIED = "died";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Map<String, Entry> data;

	public static final class Entry {
		public String name;
		public String status;
		public String dim;
		public double x;
		public double y;
		public double z;
	}

	private PwDatabase() {
	}

	public static String serverKey(Minecraft mc) {
		return mc.getCurrentServer() != null ? mc.getCurrentServer().ip.toLowerCase(Locale.ROOT) : "singleplayer";
	}

	private static String key(Minecraft mc, String name) {
		return serverKey(mc) + "|" + name.toLowerCase(Locale.ROOT);
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("khoatools-pw.json");
	}

	private static synchronized Map<String, Entry> load() {
		if (data == null) {
			data = new LinkedHashMap<>();
			Path f = file();
			if (Files.exists(f)) {
				try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
					Map<String, Entry> read = GSON.fromJson(r, new TypeToken<LinkedHashMap<String, Entry>>() { }.getType());
					if (read != null) {
						data = read;
					}
				} catch (IOException | RuntimeException e) {
					KhoaTools.LOGGER.error("Could not read " + f, e);
				}
			}
		}
		return data;
	}

	private static synchronized void save() {
		try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
			GSON.toJson(load(), w);
		} catch (IOException e) {
			KhoaTools.LOGGER.error("Could not write " + file(), e);
		}
	}

	public static synchronized Entry get(Minecraft mc, String name) {
		return load().get(key(mc, name));
	}

	/** True when this pw was already tried (teleported, did not teleport, or opened a GUI) and must not be tried again. */
	public static synchronized boolean isDone(Minecraft mc, String name) {
		Entry e = load().get(key(mc, name));
		return e != null;
	}

	public static synchronized void put(Minecraft mc, String name, String status, String dim, double x, double y, double z) {
		Entry e = new Entry();
		e.name = name;
		e.status = status;
		e.dim = dim;
		e.x = x;
		e.y = y;
		e.z = z;
		load().put(key(mc, name), e);
		save();
	}

	/** All entries of this server with the given status, in insertion order. */
	public static synchronized java.util.List<Entry> all(Minecraft mc, String status) {
		String prefix = serverKey(mc) + "|";
		java.util.List<Entry> out = new java.util.ArrayList<>();
		for (Map.Entry<String, Entry> e : load().entrySet()) {
			if (e.getKey().startsWith(prefix) && (status == null || status.equals(e.getValue().status))) {
				out.add(e.getValue());
			}
		}
		return out;
	}

	/** Removes the entries of this server with this status. Returns how many. */
	public static synchronized int clearStatus(Minecraft mc, String status) {
		String prefix = serverKey(mc) + "|";
		int before = load().size();
		load().entrySet().removeIf(e -> e.getKey().startsWith(prefix) && status.equals(e.getValue().status));
		save();
		return before - load().size();
	}

	/** Removes every entry of this server. Returns how many. */
	public static synchronized int clear(Minecraft mc) {
		String prefix = serverKey(mc) + "|";
		int before = load().size();
		load().keySet().removeIf(k -> k.startsWith(prefix));
		save();
		return before - load().size();
	}
}
