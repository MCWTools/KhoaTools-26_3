package khoa.tools.client.feature.freecam;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import khoa.tools.KhoaTools;
import khoa.tools.client.config.KhoaCheat;
import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.client.feature.camera.CameraWatch;
import khoa.tools.client.util.KeyCategory;
import khoa.tools.setting.Setting;

/**
 * Freecam card (/khoatoolsclient freecam, or the "Freecam" key in Controls > KhoaTools). The flying itself lives in
 * {@link CameraWatch}; this class owns the toggle, the settings and the HUD line. Never restored on the next start.
 * The aim options only exist while /khoagui cheat is true; without cheat the player's head aim is always used.
 */
public final class FreecamFeature implements KhoaFeature {
	private static final String ID = "freecam";
	private static KeyMapping key;
	private static boolean keyWasDown;

	@Override public String id() { return ID; }
	@Override public String displayName() { return "Freecam"; }
	@Override public String description() {
		return "Flies the camera away while your player stays where it is. WASD moves along the view direction on the horizontal plane (no acceleration), "
			+ "jump / sneak go straight up / down, mouse wheel changes both speeds. A sneaking player stays sneaking. "
			+ "By default clicks and the Auto Clicker hit what your player's head aims at, wherever the camera is; a through wall option aims with the camera instead. "
			+ "Key: Controls > KhoaTools > Freecam.";
	}

	@Override public boolean isActive() { return CameraWatch.isFree(); }

	@Override public void activate(Minecraft mc) {
		if (CameraWatch.isFree()) {
			CameraWatch.stop();
			KhoaFeature.say(mc, "[Freecam] Off.");
		} else if (mc.player != null && !KhoaCheat.isHidden(this)) {
			CameraWatch.startFree(mc);
			KhoaFeature.say(mc, "[Freecam] On.");
		}
	}

	@Override public List<Setting> settings() {
		List<Setting> rows = new ArrayList<>();
		rows.add(Setting.slider("speed_h", "Horizontal speed", "WASD speed of the freecam in blocks per second.",
			1, 100, CameraWatch::speedH, CameraWatch::setSpeedH));
		rows.add(Setting.slider("speed_v", "Vertical speed", "Jump / sneak (up / down) speed of the freecam in blocks per second.",
			1, 100, CameraWatch::speedV, CameraWatch::setSpeedV));
		if (KhoaCheat.enabled()) {
			rows.add(Setting.bool("head_aim", "Use player head aim",
				"On: clicks and the Auto Clicker hit what your player's head aims at, wherever the camera is. This is on exactly while both through wall options are off; "
					+ "turning it off turns both of them on.",
				() -> !usesThroughWall(), FreecamFeature::setHeadAim));
			rows.add(Setting.bool("entity_through_wall", "Interact with entity through wall",
				"Aim with the freecam instead of the head, and attack / use entities your player cannot see (normal reach from your player).",
				() -> KhoaConfig.getBool(ID, "entity_through_wall", false), v -> KhoaConfig.put(ID, "entity_through_wall", v)));
			rows.add(Setting.bool("block_through_wall", "Interact with block through wall",
				"Aim with the freecam instead of the head: selection box, breaking, placing and using work on the block the camera looks at, "
					+ "also behind walls (normal reach from your player).",
				() -> KhoaConfig.getBool(ID, "block_through_wall", false), v -> KhoaConfig.put(ID, "block_through_wall", v)));
		}
		return rows;
	}

	private static boolean usesThroughWall() {
		return KhoaConfig.getBool(ID, "entity_through_wall", false) || KhoaConfig.getBool(ID, "block_through_wall", false);
	}

	/** Head aim and the through wall options exclude each other: on = both off, off = both on. */
	private static void setHeadAim(boolean headAim) {
		KhoaConfig.put(ID, "entity_through_wall", !headAim);
		KhoaConfig.put(ID, "block_through_wall", !headAim);
	}

	static boolean entityThroughWall() {
		return KhoaConfig.getBool(ID, "entity_through_wall", false);
	}

	static boolean blockThroughWall() {
		return KhoaConfig.getBool(ID, "block_through_wall", false);
	}

	/** True while the crosshair target comes from the freecam instead of the player's head. */
	public static boolean usesCameraAim() {
		return CameraWatch.isFree() && KhoaCheat.enabled() && usesThroughWall();
	}

	@Override public void onInit() {
		key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.khoatools.freecam", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), KeyCategory.get()));
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			if (CameraWatch.isFree()) CameraWatch.stop();
		}));
		HudElementRegistry.addLast(KhoaTools.id("freecam_overlay"), FreecamFeature::renderHud);
	}

	private void tick(Minecraft mc) {
		CameraWatch.validate(mc);
		if (CameraWatch.isFree() && KhoaCheat.isHidden(this)) CameraWatch.stop();
		boolean down = key.isDown() && mc.gui.screen() == null;
		if (down && !keyWasDown) activate(mc);
		keyWasDown = down;
		FreecamSelfTest.tick(mc);
	}

	private static void renderHud(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!CameraWatch.isFree() || mc.level == null || mc.gui.hud.isHidden()) return;
		g.text(mc.font, String.format("Freecam  speed H %d  V %d", CameraWatch.speedH(), CameraWatch.speedV()), 10, 10, 0xFFFFFFFF);
		if (usesCameraAim()) {
			boolean entity = entityThroughWall();
			boolean block = blockThroughWall();
			g.text(mc.font, "Camera aim, through wall: " + (entity && block ? "entity, block" : entity ? "entity" : "block"), 10, 21, 0xFFFFAA55);
			// third person has no vanilla crosshair, but the camera is what aims now
			g.blitSprite(RenderPipelines.CROSSHAIR, Identifier.withDefaultNamespace("hud/crosshair"), (g.guiWidth() - 15) / 2, (g.guiHeight() - 15) / 2, 15, 15);
		}
	}
}
