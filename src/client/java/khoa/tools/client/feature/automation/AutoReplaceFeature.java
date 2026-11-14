package khoa.tools.client.feature.automation;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import khoa.tools.setting.Setting;

/** Puts the chosen block in the hole every time you break a block. Pauses itself when the block is used up. */
public final class AutoReplaceFeature extends ToggleFeature {
	private static final String ID = "autoreplace";
	private static final PickedSet BLOCK = new PickedSet(ID, "block", List.of());
	private static boolean busy;

	private BlockPos watched;
	private int watchTicks;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Auto Replace";
	}

	@Override
	public String description() {
		return "Choose one block. Every block you break is replaced by it: it takes the block from your hotbar or inventory, places it into the hole and "
			+ "returns to your slot. The block needs a solid neighbour to be placed against. When you run out of it, the feature pauses. Click the card to turn it on/off.";
	}

	@Override
	public List<Setting> settings() {
		return List.of(BLOCK.setting("Block to place", "The one block that fills the holes. Picking another one replaces it.", List.of("block")).maxPicks(1));
	}

	/** True in the tick when this feature has moved items around (other automations wait). */
	static boolean isBusy() {
		return busy;
	}

	@Override
	protected void onToggle(Minecraft mc, boolean now) {
		watched = null;
		busy = false;
	}

	@Override
	protected void tick(Minecraft mc) {
		busy = false;
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gui.screen() != null || !InvUtil.canClick(mc) || AutoEatFeature.isEating()) {
			watched = null;
			return;
		}
		if (BLOCK.blocks().isEmpty()) {
			return;
		}
		if (mc.options.keyAttack.isDown() && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
			&& !mc.level.getBlockState(hit.getBlockPos()).isAir()) {
			watched = hit.getBlockPos();
			watchTicks = 0;
			return;
		}
		if (watched == null) {
			return;
		}
		if (mc.level.getBlockState(watched).isAir()) {
			BlockPos pos = watched;
			watched = null;
			fill(mc, pos);
		} else if (++watchTicks > 10) {
			watched = null; // stopped mining before the block broke
		}
	}

	private void fill(Minecraft mc, BlockPos pos) {
		LocalPlayer player = mc.player;
		Block block = BLOCK.blocks().iterator().next();
		Item item = block.asItem();
		int selected = player.getInventory().getSelectedSlot();
		int hotbar = -1;
		int fromMain = -1;
		for (int i = 0; i < 9 && hotbar < 0; i++) {
			if (player.getInventory().getItem(i).is(item)) {
				hotbar = i;
			}
		}
		if (hotbar < 0) {
			for (int i = InvUtil.MAIN_START; i < InvUtil.MAIN_END; i++) {
				if (InvUtil.stack(mc, i).is(item)) {
					fromMain = i;
					break;
				}
			}
		}
		if (hotbar < 0 && fromMain < 0) {
			pause(mc, "no " + block.getName().getString() + " left");
			return;
		}
		BlockHitResult target = against(mc, pos);
		if (target == null) {
			return; // nothing solid next to the hole to place against
		}
		busy = true;
		if (fromMain >= 0) {
			InvUtil.swapWithHotbar(mc, fromMain, selected); // into the selected slot for one placement
			place(mc, target);
			InvUtil.swapWithHotbar(mc, fromMain, selected);
			return;
		}
		player.getInventory().setSelectedSlot(hotbar);
		place(mc, target);
		player.getInventory().setSelectedSlot(selected);
	}

	private static void place(Minecraft mc, BlockHitResult target) {
		InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, target);
		if (result.consumesAction()) {
			mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
		}
	}

	/** A click on the face of a solid neighbour that points into {@code pos}. */
	private static BlockHitResult against(Minecraft mc, BlockPos pos) {
		for (Direction dir : Direction.values()) {
			BlockPos neighbour = pos.relative(dir);
			BlockState state = mc.level.getBlockState(neighbour);
			if (state.isAir() || state.canBeReplaced() || !state.getFluidState().isEmpty()) {
				continue;
			}
			Direction face = dir.getOpposite();
			Vec3 point = Vec3.atCenterOf(neighbour).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
			return new BlockHitResult(point, face, neighbour, false);
		}
		return null;
	}
}
