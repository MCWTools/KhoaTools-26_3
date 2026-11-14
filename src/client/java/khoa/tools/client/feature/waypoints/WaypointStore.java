package khoa.tools.client.feature.waypoints;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;

import khoa.tools.KhoaTools;
import khoa.tools.client.util.ServerKey;

/**
 * Waypoints and groups on disk, per server / world:
 * <pre>
 * .minecraft/khoatools/waypoints/multiplayer/&lt;server ip&gt;/Waypoints.json   (every waypoint, each with an id)
 *                                                          /Groups.json      (groups: name, on/off, ids of the waypoints and groups inside)
 *                                                          /backup/&lt;time&gt;[_why]/...
 * .minecraft/khoatools/waypoints/&lt;world name&gt;/...                            (singleplayer)
 * </pre>
 * A backup of both files is made when a world is first opened and before every delete / unpack.
 */
public final class WaypointStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int KEEP_BACKUPS = 25;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private static final WorldData DETACHED = new WorldData();
	private static final Map<Path, WorldData> LOADED = new HashMap<>();
	private static Object lastLevel;
	private static Path lastFolder;
	private static WorldData lastData = DETACHED;
	private static boolean dirty;

	private WaypointStore() {
	}

	private static final class WaypointFile {
		int version = 1;
		List<Waypoint> waypoints = new ArrayList<>();
	}

	private static final class GroupFile {
		int version = 1;
		List<Group> groups = new ArrayList<>();
	}

	// ------------------------------------------------------------------ which world

	private static String clean(String name) {
		String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
		return s.isEmpty() ? "unknown" : s;
	}

	/** "multiplayer/<ip>" or "<world name>": the same world naming other KhoaTools data uses under its own folder. */
	public static Path worldSubfolder(Minecraft mc) {
		return FabricLoader.getInstance().getGameDir().resolve("khoatools").resolve("waypoints").relativize(folder(mc));
	}

	static Path folder(Minecraft mc) {
		Path base = FabricLoader.getInstance().getGameDir().resolve("khoatools").resolve("waypoints");
		if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
			return base.resolve(clean(XaeroImport.currentWorldName(mc)));
		}
		String ip = mc.getCurrentServer() != null && mc.getCurrentServer().ip != null ? mc.getCurrentServer().ip.toLowerCase() : "unknown";
		return base.resolve("multiplayer").resolve(clean(ip));
	}

	/** The waypoints and groups of the world you are in (an empty throw-away object on the title screen). */
	public static synchronized WorldData world(Minecraft mc) {
		if (mc.level == null) {
			return DETACHED;
		}
		if (mc.level == lastLevel && lastData != DETACHED) {
			return lastData;
		}
		Path folder = folder(mc);
		WorldData data = LOADED.get(folder);
		if (data == null) {
			data = load(mc, folder);
			LOADED.put(folder, data);
		}
		lastLevel = mc.level;
		lastFolder = folder;
		lastData = data;
		return data;
	}

	/** Compatibility helper: the waypoint list of the current world. */
	public static List<Waypoint> current(Minecraft mc) {
		return world(mc).waypoints;
	}

	// ------------------------------------------------------------------ load / save

	private static WorldData load(Minecraft mc, Path folder) {
		WorldData data = new WorldData();
		Path wpFile = folder.resolve("Waypoints.json");
		Path groupFile = folder.resolve("Groups.json");
		boolean existed = Files.exists(wpFile) || Files.exists(groupFile);
		if (existed) {
			backup(folder, "");
			try {
				if (Files.exists(wpFile)) {
					WaypointFile f = GSON.fromJson(Files.readString(wpFile, StandardCharsets.UTF_8), WaypointFile.class);
					if (f != null && f.waypoints != null) {
						data.waypoints.addAll(f.waypoints);
					}
				}
				if (Files.exists(groupFile)) {
					GroupFile f = GSON.fromJson(Files.readString(groupFile, StandardCharsets.UTF_8), GroupFile.class);
					if (f != null && f.groups != null) {
						data.groups.addAll(f.groups);
					}
				}
			} catch (IOException | RuntimeException e) {
				KhoaTools.LOGGER.error("Could not read the waypoints in {} (a backup was made in its backup folder)", folder, e);
			}
		} else {
			migrateOld(mc, data);
		}
		data.refresh();
		if (!existed && !data.waypoints.isEmpty()) {
			write(folder, data);
		}
		return data;
	}

	/** Waypoints saved by the first version (config/khoatools_waypoints.json) are moved into the new files once. */
	private static void migrateOld(Minecraft mc, WorldData data) {
		Path old = FabricLoader.getInstance().getConfigDir().resolve("khoatools_waypoints.json");
		if (!Files.exists(old)) {
			return;
		}
		try {
			Type type = new TypeToken<HashMap<String, ArrayList<Waypoint>>>() { }.getType();
			Map<String, ArrayList<Waypoint>> all = GSON.fromJson(Files.readString(old, StandardCharsets.UTF_8), type);
			List<Waypoint> mine = all == null ? null : all.get(ServerKey.display(mc));
			if (mine != null) {
				data.waypoints.addAll(mine);
			}
		} catch (IOException | RuntimeException e) {
			KhoaTools.LOGGER.warn("Could not migrate the old waypoint file", e);
		}
	}

	private static void write(Path folder, WorldData data) {
		try {
			Files.createDirectories(folder);
			WaypointFile wf = new WaypointFile();
			wf.waypoints = data.waypoints;
			GroupFile gf = new GroupFile();
			gf.groups = data.groups;
			atomicWrite(folder.resolve("Waypoints.json"), GSON.toJson(wf));
			atomicWrite(folder.resolve("Groups.json"), GSON.toJson(gf));
		} catch (IOException e) {
			KhoaTools.LOGGER.error("Could not save the waypoints in {}", folder, e);
		}
	}

	private static void atomicWrite(Path file, String text) throws IOException {
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temp, text, StandardCharsets.UTF_8);
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
	}

	/** Copies both files into backup/&lt;time&gt;[_why]. Keeps the newest {@value #KEEP_BACKUPS}. */
	private static void backup(Path folder, String why) {
		try {
			Path dir = folder.resolve("backup").resolve(LocalDateTime.now().format(STAMP) + (why.isEmpty() ? "" : "_" + why));
			Files.createDirectories(dir);
			for (String name : List.of("Waypoints.json", "Groups.json")) {
				if (Files.exists(folder.resolve(name))) {
					Files.copy(folder.resolve(name), dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
				}
			}
			try (Stream<Path> all = Files.list(folder.resolve("backup"))) {
				List<Path> dirs = all.filter(Files::isDirectory).sorted().collect(Collectors.toList());
				for (int i = 0; i < dirs.size() - KEEP_BACKUPS; i++) {
					try (Stream<Path> files = Files.walk(dirs.get(i))) {
						files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
					}
				}
			}
		} catch (IOException e) {
			KhoaTools.LOGGER.warn("Could not back up the waypoints in {}", folder, e);
		}
	}

	/** Makes a backup of what is on disk right now (call before deleting / unpacking). */
	public static synchronized void backupNow(String why) {
		if (lastFolder != null && lastData != DETACHED) {
			saveNow(); // so the backup is the latest state before the destructive change
			backup(lastFolder, why);
		}
	}

	public static synchronized void saveNow() {
		if (lastFolder != null && lastData != DETACHED) {
			write(lastFolder, lastData);
		}
		dirty = false;
		WorldData.touch();
	}

	public static void markDirty() {
		dirty = true;
	}

	/** Writes the files if something changed (called now and then from the client tick). */
	public static void saveIfDirty() {
		if (dirty) {
			synchronized (WaypointStore.class) {
				if (lastFolder != null && lastData != DETACHED) {
					write(lastFolder, lastData);
				}
				dirty = false;
			}
		}
	}

	// ------------------------------------------------------------------ edits

	public static synchronized void add(Minecraft mc, Waypoint waypoint) {
		WorldData data = world(mc);
		data.waypoints.add(waypoint);
		data.refresh();
		saveNow();
	}

	public static synchronized void addAll(Minecraft mc, Collection<Waypoint> waypoints) {
		WorldData data = world(mc);
		data.waypoints.addAll(waypoints);
		data.refresh();
		saveNow();
	}

	public static synchronized void remove(Minecraft mc, Collection<Waypoint> waypoints) {
		WorldData data = world(mc);
		Set<String> ids = waypoints.stream().map(w -> w.id).collect(Collectors.toSet());
		data.deleteWaypoints(ids);
		saveNow();
	}
}
