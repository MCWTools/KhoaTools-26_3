package khoa.tools.client.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

import net.minecraft.client.Minecraft;

import khoa.tools.KhoaTools;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.client.feature.KhoaFeatures;

/**
 * The hidden "cheat" switch (/khoagui cheat true|false, saved in config/khoatools.json -> general.cheat, default true).
 * While it is false, every feature that {@link KhoaFeature#isIllegal()} is hidden from the GUI and from the /khoatoolsclient
 * suggestions, cannot be run (command, keybind, restore on start) and is switched off. The saved settings, keybinds and
 * command binds of those features stay in the config, so turning cheat back on restores everything.
 */
public final class KhoaCheat {
	private static final String SECTION = "general";
	private static final String KEY = "cheat";

	/** Ids of the features that give an unfair advantage on multiplayer servers (ESP, finders, world download, air placing...). */
	public static final Set<String> ILLEGAL_IDS = Set.of(
		"blockfinder", "spawnerfinder", "mobfinder",
		"downloadchunk", "loaddownloadedchunk",
		"airplace", "ghostitem",
		"visiblebarrier", "camera", "autofish", "autodrop", "autoeat");

	private static volatile Boolean enabled;

	private KhoaCheat() {
	}

	public static boolean enabled() {
		Boolean value = enabled;
		if (value == null) {
			value = KhoaConfig.getBool(SECTION, KEY, true);
			enabled = value;
		}
		return value;
	}

	/**
	 * True while this feature must not be shown or used: it is listed in config/khoatools-hidden.txt (always hidden),
	 * or it is illegal and cheat is off.
	 */
	public static boolean isHidden(KhoaFeature feature) {
		return isHiddenId(feature.id(), feature.isIllegal());
	}

	public static boolean isHiddenId(String id, boolean illegal) {
		return hiddenByFile().contains(norm(id)) || (illegal && !enabled());
	}

	/** True if the feature id or card name is listed in the hidden file. */
	public static boolean isListed(KhoaFeature feature) {
		Set<String> listed = hiddenByFile();
		return listed.contains(norm(feature.id())) || listed.contains(norm(feature.displayName()));
	}

	private static String norm(String text) {
		return text.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]+", "");
	}

	private static Path hiddenFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("khoatools-hidden.txt");
	}

	private static final String NL = System.lineSeparator();
	private static volatile Set<String> hiddenNames;

	private static Set<String> hiddenByFile() {
		Set<String> names = hiddenNames;
		if (names == null) {
			names = loadHidden();
			hiddenNames = names;
		}
		return names;
	}

	/** Re-reads config/khoatools-hidden.txt (one feature id or name per line, # = comment). Creates a template if missing. */
	private static Set<String> loadHidden() {
		Set<String> names = new HashSet<>();
		Path file = hiddenFile();
		try {
			if (!Files.exists(file)) {
				StringBuilder template = new StringBuilder("# KhoaTools hidden features." + NL
					+ "# Write one feature id (or card name) per line to hide it from the GUI and from /khoatoolsclient." + NL
					+ "# Hidden features cannot be used and are switched off. Lines starting with # are ignored." + NL
					+ "# Apply changes with /khoagui reload (or restart). Available ids:" + NL);
				for (KhoaFeature feature : KhoaFeatures.all()) {
					template.append("# ").append(feature.id()).append(NL);
				}
				Files.createDirectories(file.getParent());
				Files.writeString(file, template.toString(), StandardCharsets.UTF_8);
				return names;
			}
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				String text = line.strip();
				if (!text.isEmpty() && !text.startsWith("#")) {
					names.add(norm(text));
				}
			}
		} catch (IOException e) {
			KhoaTools.LOGGER.error("Could not read khoatools-hidden.txt", e);
		}
		return names;
	}

	/** Re-reads the hidden file, switches off what is now hidden and refreshes the command suggestions. */
	public static void reload() {
		hiddenNames = null;
		apply();
	}

	/**
	 * Saves the switch and applies it at once. Turning it off switches the active illegal features off. The command tree is rebuilt
	 * ({@link ClientCommands#refreshCommandCompletions()}: Fabric re-copies only the nodes whose requirement passes), so the
	 * suggestions change immediately. Call on the client thread.
	 */
	public static void set(boolean value) {
		enabled = value;
		KhoaConfig.put(SECTION, KEY, value);
		apply();
	}

	private static void apply() {
		Minecraft mc = Minecraft.getInstance();
		for (KhoaFeature feature : KhoaFeatures.all()) {
			if (isHidden(feature) && feature.isActive() && !feature.id().equals("camera")) { // camera gates itself (its on/off is its own saved setting)
				try {
					feature.activate(mc);
				} catch (RuntimeException e) {
					KhoaTools.LOGGER.error("Could not switch off {}", feature.id(), e);
				}
			}
		}
		try {
			ClientCommands.refreshCommandCompletions();
		} catch (RuntimeException e) {
			// Not connected yet (no commands packet received): the tree is built correctly on the next join.
			KhoaTools.LOGGER.debug("Command completions not refreshed: {}", e.getMessage());
		}
	}
}
