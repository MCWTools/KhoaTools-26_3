package khoa.tools.client.feature.blocktext;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;

import khoa.tools.KhoaTools;
import khoa.tools.client.feature.waypoints.WaypointStore;

/** Texts of the world / server you are in: .minecraft/khoatools/blocktext/(multiplayer/&lt;ip&gt; or &lt;world&gt;)/BlockText.json */
final class BlockTextStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final class FileData {
		int version = 1;
		List<BlockText> texts = new ArrayList<>();
	}

	private static Object level;
	private static Path folder;
	private static List<BlockText> texts = new ArrayList<>();

	private BlockTextStore() {
	}

	private static Path folder(Minecraft mc) {
		return FabricLoader.getInstance().getGameDir().resolve("khoatools").resolve("blocktext").resolve(WaypointStore.worldSubfolder(mc));
	}

	/** The texts of the current world (loaded when you join). Empty on the title screen. */
	static synchronized List<BlockText> all(Minecraft mc) {
		if (mc.level == null) {
			return List.of();
		}
		if (mc.level != level) {
			level = mc.level;
			folder = folder(mc);
			texts = load(folder);
		}
		return texts;
	}

	private static List<BlockText> load(Path dir) {
		Path file = dir.resolve("BlockText.json");
		if (!Files.exists(file)) {
			return new ArrayList<>();
		}
		try {
			FileData data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), (Type) FileData.class);
			if (data != null && data.texts != null) {
				return new ArrayList<>(data.texts);
			}
		} catch (IOException | JsonSyntaxException e) {
			KhoaTools.LOGGER.error("Could not read {} (the file is left untouched)", file, e);
			try {
				Files.copy(file, dir.resolve("BlockText.broken.json"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
				// the original file stays in place anyway
			}
		}
		return new ArrayList<>();
	}

	static synchronized void save(Minecraft mc) {
		if (mc.level == null || folder == null || mc.level != level) {
			return;
		}
		try {
			Files.createDirectories(folder);
			FileData data = new FileData();
			data.texts = texts;
			Path file = folder.resolve("BlockText.json");
			Path temp = file.resolveSibling("BlockText.json.tmp");
			Files.writeString(temp, GSON.toJson(data), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			KhoaTools.LOGGER.error("Could not save the block texts in {}", folder, e);
		}
	}

	static BlockText find(Minecraft mc, String dim, int x, int y, int z, String face) {
		for (BlockText t : all(mc)) {
			if (t.x == x && t.y == y && t.z == z && t.face.equals(face) && t.dim.equals(dim)) {
				return t;
			}
		}
		return null;
	}
}
