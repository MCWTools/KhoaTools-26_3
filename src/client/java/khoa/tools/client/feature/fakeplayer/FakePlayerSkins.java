package khoa.tools.client.feature.fakeplayer;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;

import khoa.tools.KhoaTools;

/** Finds the skin of a fake player: a Mojang player name (looked up online) or a local .png file. */
public final class FakePlayerSkins {
	private static final Map<String, PlayerSkin> FILE_CACHE = new HashMap<>();

	private FakePlayerSkins() {
	}

	/** True when the text should be read as a file path. */
	public static boolean isFile(String source) {
		return source != null && source.toLowerCase().endsWith(".png");
	}

	/** Starts the lookup; {@code done} runs on the client thread with the skin (never called when nothing was found). */
	public static void resolve(FakePlayerData data, Consumer<PlayerSkin> done) {
		Minecraft mc = Minecraft.getInstance();
		String source = data.skin == null ? "" : data.skin.trim();
		if (isFile(source)) {
			PlayerSkin skin = file(source, data.slim);
			if (skin != null) done.accept(skin);
			return;
		}
		String player = source.isEmpty() ? data.name : source;
		if (player == null || player.isBlank()) return;
		mc.playerSkinRenderCache().lookup(ResolvableProfile.createUnresolved(player)).thenAccept(info -> {
			if (info.isPresent()) {
				PlayerSkin skin = info.get().playerSkin();
				mc.execute(() -> done.accept(skin));
			}
		});
	}

	/** Loads (and caches) a local skin png. Returns null when the file cannot be read. */
	public static PlayerSkin file(String path, boolean slim) {
		String key = path + "|" + slim;
		if (FILE_CACHE.containsKey(key)) return FILE_CACHE.get(key);
		PlayerSkin skin = null;
		try (InputStream in = Files.newInputStream(Path.of(path))) {
			NativeImage image = NativeImage.read(in);
			Identifier id = KhoaTools.id("fakeplayer_skin/" + Integer.toHexString(key.hashCode()));
			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "khoatools fake player skin", image));
			skin = PlayerSkin.insecure(new ClientAsset.ResourceTexture(id), null, null, slim ? PlayerModelType.SLIM : PlayerModelType.WIDE);
		} catch (Exception e) {
			KhoaTools.LOGGER.warn("Could not load fake player skin '{}'", path, e);
		}
		FILE_CACHE.put(key, skin);
		return skin;
	}
}
