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
 * The two "through wall" options only exist and only work while /khoagui cheat is true.
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
			+ "Clicks and the Auto Clicker still hit what your player's head aims at, wherever the camera is. Key: Controls > KhoaTools > Freecam.";
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
			rows.add(Setting.bool("entity_through_wall", "Interact with entity through wall",
				"While in freecam your player can attack / use an entity it aims at even with blocks in between (normal reach).",
				() -> KhoaConfig.getBool(ID, "entity_through_wall", false), v -> KhoaConfig.put(ID, "entity_through_wall", v)));
			rows.add(Setting.bool("block_through_wall", "Interact with block through wall",
				"While in freecam your player's right click reaches the first usable block it aims at (chest, door, lever...) behind other blocks (normal reach). Mining is not changed.",
				() -> KhoaConfig.getBool(ID, "block_through_wall", false), v -> KhoaConfig.put(ID, "block_through_wall", v)));
		}
		return rows;
	}

	static boolean entityThroughWall() {
		return CameraWatch.isFree() && KhoaCheat.enabled() && KhoaConfig.getBool(ID, "entity_through_wall", false);
	}

	static boolean blockThroughWall() {
		return CameraWatch.isFree() && KhoaCheat.enabled() && KhoaConfig.getBool(ID, "block_through_wall", false);
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
		boolean entity = entityThroughWall();
		boolean block = blockThroughWall();
		if (entity || block) {
			g.text(mc.font, "Through wall: " + (entity && block ? "entity, block" : entity ? "entity" : "block"), 10, 21, 0xFFFFAA55);
		}
	}
}
