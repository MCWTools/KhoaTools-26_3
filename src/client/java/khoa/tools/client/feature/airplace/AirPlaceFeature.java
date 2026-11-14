package khoa.tools.client.feature.airplace;

import java.util.List;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.setting.Setting;

/**
 * Air Place (client side): while you hold a block and look at nothing, right click places the block in the air at a set distance in front
 * of your crosshair (a box shows where). Looking at a block works as normal. Servers still check reach (vanilla accepts about 5-6 blocks),
 * so a larger distance only works in singleplayer or on servers that allow it.
 */
public final class AirPlaceFeature implements KhoaFeature {
	private static final String ID = "airplace";
	private static boolean on;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Air Place";
	}

	@Override
	public String description() {
		return "Click the card to turn it on/off. Holding a block and looking at nothing, right click places the block in the air at the chosen "
			+ "distance (a box shows the spot). Vanilla servers only accept about 5-6 blocks.";
	}

	@Override
	public List<Setting> settings() {
		return List.of(Setting.slider("distance", "Distance (blocks)", "How far in front of you the block is placed.", 1, 12,
			AirPlaceFeature::distance, value -> KhoaConfig.put(ID, "distance", Math.max(1, Math.min(12, value)))));
	}

	@Override
	public boolean persistEnabled() {
		return true;
	}

	@Override
	public boolean isActive() {
		return on;
	}

	@Override
	public void activate(Minecraft mc) {
		on = !on;
		KhoaFeature.say(mc, "Air Place: " + (on ? "on (" + distance() + " blocks)" : "off"));
	}

	@Override
	public void onInit() {
		LevelRenderEvents.END_EXTRACTION.register(context -> drawPreview(Minecraft.getInstance()));
	}

	private static int distance() {
		return Math.max(1, Math.min(12, KhoaConfig.getInt(ID, "distance", 4)));
	}

	/** The air block the next right click would fill, or null when air place does not apply right now. */
	private static BlockPos target(Minecraft mc) {
		if (!on || mc.player == null || mc.level == null || mc.gameMode == null || mc.gui.screen() != null) {
			return null;
		}
		if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) {
			return null;
		}
		if (!(mc.player.getMainHandItem().getItem() instanceof BlockItem)) {
			return null;
		}
		Vec3 at = mc.player.getEyePosition().add(mc.player.getViewVector(1.0F).scale(distance()));
		BlockPos pos = BlockPos.containing(at);
		if (mc.level.isOutsideBuildHeight(pos) || !mc.level.getBlockState(pos).canBeReplaced()) {
			return null;
		}
		return pos;
	}

	/** Called by the mixin on right click. True = handled (the normal use is skipped). */
	public static boolean onUse(Minecraft mc) {
		BlockPos pos = target(mc);
		if (pos == null) {
			return false;
		}
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.DOWN, pos, false);
		InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
		if (result.consumesAction()) {
			mc.player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
		}
		return true;
	}

	private static void drawPreview(Minecraft mc) {
		BlockPos pos = target(mc);
		if (pos != null) {
			Gizmos.cuboid(new AABB(pos).inflate(0.003), GizmoStyle.strokeAndFill(0xFFFFFFFF, 2.0F, 0x30FFFFFF));
		}
	}
}
