package khoa.tools.client.feature.invrefill;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Where Save and Load Inventories keeps its files. Newer versions write {@code config/serilum/saveandloadinventories/<name>.txt}
 * (Collective's config folder), older ones {@code config/saveandloadinventories/<name>.txt}; both are searched, newest first.
 */
final class SavedInventoryFiles {
	private SavedInventoryFiles() {
	}

	private static List<Path> dirs() {
		Path config = FabricLoader.getInstance().getConfigDir();
		return List.of(config.resolve("serilum").resolve(SavedInventoryRefill.MOD_ID), config.resolve(SavedInventoryRefill.MOD_ID));
	}

	static List<String> names() {
		return SavedInventoryRefill.listNames(dirs());
	}

	/** The saved file for {@code name} (already lower-case), or null. Names come from a brigadier word, so they hold no path separators. */
	static Path find(String name) {
		if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
			return null;
		}
		for (Path dir : dirs()) {
			Path file = dir.resolve(name + ".txt");
			if (Files.isRegularFile(file)) {
				return file;
			}
		}
		return null;
	}
}
