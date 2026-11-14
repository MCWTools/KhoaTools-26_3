package khoa.tools.client.feature.automation;

import java.util.List;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import khoa.tools.KhoaTools;
import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.client.mixin.MultiPlayerGameModeAccessor;
import khoa.tools.setting.Setting;

/**
 * A small see-through panel beside the crosshair while you mine: the block, how far it is broken and the estimated time left
 * (from the same per-tick progress the game uses for your tool, enchantments and effects).
 */
public final class SmartMiningFeature implements KhoaFeature {
	private static final String ID = "smartmining";
	private static boolean on;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Smart Mining";
	}

	@Override
	public String description() {
		return "While you mine a block, shows next to the crosshair which block it is, the break progress, its hardness and the estimated time left. "
			+ "The panel is see-through so you can still see the block (the block behind lava or water is shown too). Click the card to turn it on/off.";
	}

	@Override
	public List<Setting> settings() {
		return List.of(
			Setting.slider("opacity", "Opacity (%)", "How solid the panel is. Low = easier to see through.", 10, 100,
				() -> KhoaConfig.getInt(ID, "opacity", 45), v -> KhoaConfig.put(ID, "opacity", v)),
			Setting.bool("always", "Show when not mining", "Also shows the block you look at (without progress) when you are not holding Attack.",
				() -> KhoaConfig.getBool(ID, "always", false), v -> KhoaConfig.put(ID, "always", v)));
	}

	@Override
	public boolean isActive() {
		return on;
	}

	@Override
	public boolean persistEnabled() {
		return true;
	}

	@Override
	public void activate(Minecraft mc) {
		on = !on;
		KhoaFeature.say(mc, "Smart Mining: " + (on ? "on" : "off"));
	}

	@Override
	public void onInit() {
		HudElementRegistry.addLast(KhoaTools.id("smart_mining"), SmartMiningFeature::render);
	}

	private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!on || mc.player == null || mc.level == null || mc.gameMode == null || mc.gui.hud.isHidden() || mc.gui.screen() != null) {
			return;
		}
		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		BlockPos pos = hit.getBlockPos();
		BlockState state = mc.level.getBlockState(pos);
		if (state.isAir()) {
			return;
		}
		boolean mining = mc.gameMode.isDestroying();
		if (!mining && !KhoaConfig.getBool(ID, "always", false)) {
			return;
		}
		int alpha = Math.round(255 * KhoaConfig.getInt(ID, "opacity", 45) / 100f);
		int fg = (alpha << 24) | 0xFFFFFF;
		int dim = (Math.round(alpha * 0.75f) << 24) | 0xBBBBBB;
		int shade = (Math.round(alpha * 0.55f) << 24);

		float hardness = state.getDestroySpeed(mc.level, pos);
		String name = state.getBlock().getName().getString();
		String line2;
		String line3 = "Hardness " + (hardness < 0 ? "unbreakable" : trim(hardness));
		float progress = 0F;
		if (hardness < 0) {
			line2 = "Cannot be broken";
		} else if (!mining) {
			line2 = "Hold Attack to mine";
		} else {
			progress = Math.min(1F, ((MultiPlayerGameModeAccessor) mc.gameMode).khoa$getDestroyProgress());
			float perTick = state.getDestroyProgress(mc.player, mc.level, pos);
			String left = perTick <= 0F ? "never" : String.format("%.1f s", Math.ceil((1F - progress) / perTick) * 0.05);
			line2 = Math.round(progress * 100) + "%  -  " + left;
		}

		int w = Math.max(mc.font.width(name), Math.max(mc.font.width(line2), mc.font.width(line3))) + 26;
		int h = 30;
		int x = g.guiWidth() / 2 + 14;
		int y = g.guiHeight() / 2 - h / 2;
		g.fill(x, y, x + w, y + h, shade);
		if (mining) {
			g.fill(x, y + h - 2, x + Math.round(w * progress), y + h, (alpha << 24) | 0x55FF77);
		}
		g.item(new ItemStack(state.getBlock().asItem()), x + 3, y + 7);
		g.text(mc.font, name, x + 23, y + 3, fg, false);
		g.text(mc.font, line2, x + 23, y + 12, fg, false);
		g.text(mc.font, line3, x + 23, y + 21, dim, false);
	}

	private static String trim(float v) {
		return v == Math.floor(v) ? String.valueOf((int) v) : String.format("%.2f", v);
	}
}
