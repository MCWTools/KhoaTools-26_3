package khoa.tools.client.feature.invprofile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;

import khoa.tools.KhoaTools;

/** Inventory profiles, saved once for every world and server: .minecraft/khoatools/invprofiles.json. */
public final class InvProfileStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final List<InvProfile> PROFILES = new ArrayList<>();
	private static boolean loaded;

	private InvProfileStore() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getGameDir().resolve("khoatools").resolve("invprofiles.json");
	}

	public static synchronized List<InvProfile> all() {
		if (!loaded) {
			load();
		}
		return PROFILES;
	}

	public static synchronized InvProfile find(String id) {
		for (InvProfile p : all()) {
			if (p.id.equals(id)) {
				return p;
			}
		}
		return null;
	}

	public static synchronized InvProfile findByName(String name) {
		for (InvProfile p : all()) {
			if (p.name.equalsIgnoreCase(name)) {
				return p;
			}
		}
		return null;
	}

	public static synchronized InvProfile create() {
		InvProfile p = new InvProfile();
		p.name = "Profile " + (all().size() + 1);
		PROFILES.add(p);
		save();
		return p;
	}

	public static synchronized void remove(InvProfile p) {
		PROFILES.remove(p);
		save();
	}

	private static void load() {
		loaded = true;
		PROFILES.clear();
		Path file = file();
		if (!Files.isRegularFile(file)) {
			return;
		}
		try {
			List<InvProfile> read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<InvProfile>>() {
			}.getType());
			if (read != null) {
				for (InvProfile p : read) {
					p.normalize();
					PROFILES.add(p);
				}
			}
		} catch (IOException | JsonSyntaxException e) {
			KhoaTools.LOGGER.error("Could not read {}", file, e);
		}
	}

	public static synchronized void save() {
		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling("invprofiles.json.tmp");
			Files.writeString(temp, GSON.toJson(PROFILES), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			KhoaTools.LOGGER.error("Could not save {}", file, e);
		}
	}
}
