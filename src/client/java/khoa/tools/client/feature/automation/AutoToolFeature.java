package khoa.tools.client.feature.automation;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.setting.Setting;

/** While you mine, switches to the hotbar tool that breaks the block you look at fastest. */
public final class AutoToolFeature extends ToggleFeature {
	private static final String ID = "autotool";
	private int previous = -1;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Auto Tool";
	}

	@Override
	public String description() {
		return "While you hold Attack on a block, selects the hotbar slot whose item breaks it best (a tool that gives drops first, then the fastest). "
			+ "Click the card to turn it on/off.";
	}

	@Override
	public List<Setting> settings() {
		return List.of(Setting.bool("switch_back", "Switch back", "Returns to the slot you had when you stop mining.",
			() -> KhoaConfig.getBool(ID, "switch_back", true), v -> KhoaConfig.put(ID, "switch_back", v)));
	}

	@Override
	protected boolean atStart() {
		return true; // before the game starts breaking the block, so no progress is lost to a late switch
	}

	@Override
	protected void onToggle(Minecraft mc, boolean now) {
		if (!now) {
			restore(mc);
		}
	}

	@Override
	protected void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gui.screen() != null || AutoEatFeature.isEating()) {
			previous = -1;
			return;
		}
		if (mc.options.keyAttack.isDown() && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			BlockState state = mc.level.getBlockState(hit.getBlockPos());
			if (!state.isAir()) {
				Inventory inventory = player.getInventory();
				if (previous < 0) {
					previous = inventory.getSelectedSlot();
				}
				int best = inventory.getSelectedSlot();
				double bestScore = score(inventory.getItem(best), state);
				for (int i = 0; i < 9; i++) {
					double s = score(inventory.getItem(i), state);
					if (s > bestScore) {
						best = i;
						bestScore = s;
					}
				}
				if (best != inventory.getSelectedSlot()) {
					inventory.setSelectedSlot(best);
				}
				return;
			}
		}
		restore(mc);
	}

	private static double score(ItemStack stack, BlockState state) {
		return (stack.isCorrectToolForDrops(state) ? 1000.0 : 0.0) + stack.getDestroySpeed(state);
	}

	private void restore(Minecraft mc) {
		if (previous >= 0 && mc.player != null && KhoaConfig.getBool(ID, "switch_back", true)) {
			mc.player.getInventory().setSelectedSlot(previous);
		}
		previous = -1;
	}
}
