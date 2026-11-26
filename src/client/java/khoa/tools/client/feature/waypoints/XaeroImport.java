package khoa.tools.client.feature.waypoints;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

import khoa.tools.KhoaTools;

/**
 * Reads Xaero's Minimap waypoint files: .minecraft/xaero/minimap/&lt;world&gt;/dim%&lt;dimension&gt;/*.txt, lines like
 * {@code waypoint:name:initials:x:y:z:color:disabled:type:set:...}. Death points of Xaero (name gui.xaero_deathpoint[_old]) become death points here.
 */
public final class XaeroImport {
	/** A Xaero world folder with the number of waypoints found in it. */
	public record Folder(Path path, String address, int count, boolean thisWorld, boolean multiplayer) {
	}

	public record Result(int imported, int skipped) {
	}

	private XaeroImport() {
	}

	public static Path root() {
		return FabricLoader.getInstance().getGameDir().resolve("xaero").resolve("minimap");
	}

	/** Every Xaero world folder that has waypoints; the ones matching the current world come first. */
	public static List<Folder> folders(Minecraft mc) {
		List<Folder> out = new ArrayList<>();
		Path root = root();
		if (!Files.isDirectory(root)) {
			return out;
		}
		String mine = normalize(currentWorldName(mc), isMultiplayer(mc));
		try (Stream<Path> dirs = Files.list(root)) {
			for (Path dir : (Iterable<Path>) dirs.filter(Files::isDirectory).sorted()::iterator) {
				List<Waypoint> found = read(dir);
				if (found.isEmpty()) {
					continue;
				}
				String name = dir.getFileName().toString();
				boolean multiplayer = name.startsWith("Multiplayer_");
				boolean match = multiplayer == isMultiplayer(mc) && !mine.isEmpty() && normalize(name, multiplayer).equals(mine);
				out.add(new Folder(dir, address(name), found.size(), match, multiplayer));
			}
		} catch (IOException e) {
			KhoaTools.LOGGER.error("Could not list the Xaero minimap folder", e);
		}
		out.sort((a, b) -> Boolean.compare(b.thisWorld(), a.thisWorld()));
		return out;
	}

	/** Imports a folder into the current world's list (duplicates with the same name, position and dimension are skipped). */
	public static Result importFolder(Minecraft mc, Folder folder) {
		List<Waypoint> existing = WaypointStore.current(mc);
		List<Waypoint> add = new ArrayList<>();
		int skipped = 0;
		long now = System.currentTimeMillis();
		int oldIndex = 0;
		for (Waypoint w : read(folder.path())) {
			if (w.death) {
				w.created = w.name.endsWith("_old") ? now - 1000L * (++oldIndex) - 60_000L : now - 30_000L;
				w.modified = w.created;
				w.name = "Death";
			} else {
				w.created = now;
				w.modified = now;
			}
			if (duplicate(existing, w) || duplicate(add, w)) {
				skipped++;
			} else {
				add.add(w);
			}
		}
		if (!add.isEmpty()) {
			WaypointStore.addAll(mc, add);
		}
		return new Result(add.size(), skipped);
	}

	private static boolean duplicate(List<Waypoint> list, Waypoint w) {
		for (Waypoint other : list) {
			if (other.x == w.x && other.y == w.y && other.z == w.z && other.dim.equals(w.dim) && other.death == w.death
				&& (w.death || other.name.equals(w.name))) {
				return true;
			}
		}
		return false;
	}

	private static List<Waypoint> read(Path worldFolder) {
		List<Waypoint> out = new ArrayList<>();
		try (Stream<Path> dims = Files.list(worldFolder)) {
			for (Path dir : (Iterable<Path>) dims.filter(p -> Files.isDirectory(p) && p.getFileName().toString().startsWith("dim%")).sorted()::iterator) {
				String dim = dimension(dir.getFileName().toString().substring(4));
				try (Stream<Path> files = Files.list(dir)) {
					for (Path file : (Iterable<Path>) files.filter(p -> p.getFileName().toString().endsWith(".txt")).sorted()::iterator) {
						for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
							Waypoint w = parse(line, dim);
							if (w != null) {
								out.add(w);
							}
						}
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			KhoaTools.LOGGER.warn("Could not read Xaero waypoints in {}", worldFolder, e);
		}
		return out;
	}

	private static Waypoint parse(String line, String dim) {
		if (!line.startsWith("waypoint:")) {
			return null;
		}
		String[] p = line.split(":", -1);
		if (p.length < 7) {
			return null;
		}
		try {
			Waypoint w = new Waypoint();
			String name = p[1].replace("§§", ":");
			w.name = name.isBlank() ? "Waypoint" : name;
			w.x = Integer.parseInt(p[3].trim());
			w.y = Integer.parseInt(p[4].trim());
			w.z = Integer.parseInt(p[5].trim());
			int color = Integer.parseInt(p[6].trim());
			w.rgb = WaypointBadge.XAERO[Math.floorMod(color, WaypointBadge.XAERO.length)];
			w.visible = p.length < 8 || !Boolean.parseBoolean(p[7].trim());
			w.dim = dim;
			if (name.startsWith("gui.xaero_deathpoint")) {
				w.death = true;
				w.rgb = WaypointBadge.DEATH_RGB;
				w.ticksLeft = -1; // the real timer is unknown for an imported point
			}
			return w;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** dim%0 -> overworld, dim%-1 -> nether, dim%1 -> end, dim%minecraft$name%sub -> minecraft:name/sub */
	private static String dimension(String folder) {
		return switch (folder) {
			case "0" -> "minecraft:overworld";
			case "-1" -> "minecraft:the_nether";
			case "1" -> "minecraft:the_end";
			default -> folder.replace('$', ':').replace('%', '/');
		};
	}

	private static boolean isMultiplayer(Minecraft mc) {
		return !mc.hasSingleplayerServer();
	}

	static String currentWorldName(Minecraft mc) {
		if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
			try {
				Path root = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
				if (root.getFileName() != null) {
					return root.getFileName().toString();
				}
			} catch (RuntimeException ignored) {
				// fall back to the level name
			}
			return mc.getSingleplayerServer().getWorldData().getLevelName();
		}
		return mc.getCurrentServer() != null && mc.getCurrentServer().ip != null ? mc.getCurrentServer().ip : "";
	}

	/** Folder / world name -> comparable text (no Multiplayer_ prefix, no port, %us% = underscore, lower case). */
	private static String normalize(String name, boolean multiplayer) {
		String s = name;
		if (multiplayer) {
			if (s.startsWith("Multiplayer_")) {
				s = s.substring("Multiplayer_".length());
			}
			int cut = s.indexOf(':');
			if (cut >= 0) {
				s = s.substring(0, cut);
			}
			cut = s.indexOf("%cl%");
			if (cut >= 0) {
				s = s.substring(0, cut);
			}
		}
		return s.replace("%us%", "_").toLowerCase(Locale.ROOT).trim();
	}

	/** Folder name -> the server address or world name it stands for. */
	private static String address(String folderName) {
		String s = folderName.startsWith("Multiplayer_") ? folderName.substring("Multiplayer_".length()) : folderName;
		return s.replace("%us%", "_").replace("%cl%", ":");
	}

	/** True when a server address (port ignored) is the one this multiplayer folder belongs to. */
	static boolean sameAddress(Folder folder, String ip) {
		return ip != null && folder.multiplayer() && normalize(folder.path().getFileName().toString(), true).equals(normalize(ip, true));
	}
}
