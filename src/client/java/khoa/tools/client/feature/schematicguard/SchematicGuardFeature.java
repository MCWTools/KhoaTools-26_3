package khoa.tools.client.feature.schematicguard;

import java.util.List;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import khoa.tools.KhoaTools;
import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.setting.Setting;

/**
 * Schematic Guard (needs Litematica): while on, a block placement whose target position is not a block of the current Litematica
 * layer is not sent to the server. It does not care whether the block would match the schematic, only whether the spot is inside it.
 */
public final class SchematicGuardFeature implements KhoaFeature {
	private static final String ID = "schematicguard";
	private static boolean on;
	private static boolean broken;
	private static long lastMessage;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Schematic Guard";
	}

	@Override
	public String description() {
		return "Needs Litematica. While on, a block you place outside the blocks of the current Litematica layer is not sent to the server "
			+ "(it does not matter if the block is the right one, only if the spot belongs to the schematic layer). "
			+ "Does nothing while Litematica rendering is off, the placement is disabled/hidden, or the spot is outside the placement area. Clicking chests, doors, buttons... still works.";
	}

	@Override
	public List<Setting> settings() {
		return List.of(Setting.bool("message", "Show message", "Shows a message above the hotbar when a placement is stopped.",
			SchematicGuardFeature::showMessage, value -> KhoaConfig.put(ID, "message", value)));
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
		if (on && !FabricLoader.getInstance().isModLoaded("litematica")) {
			KhoaFeature.say(mc, "Schematic Guard: on, but Litematica is not installed so nothing is guarded.");
		} else {
			KhoaFeature.say(mc, "Schematic Guard: " + (on ? "on" : "off"));
		}
	}

	private static boolean showMessage() {
		return KhoaConfig.getBool(ID, "message", true);
	}

	/** Called by the mixin before a block use is sent. True = do not send it. */
	public static boolean blocks(Minecraft mc, LocalPlayer player, InteractionHand hand, BlockHitResult hit) {
		if (!on || broken || mc.level == null || !FabricLoader.getInstance().isModLoaded("litematica")) {
			return false;
		}
		ItemStack stack = player.getItemInHand(hand);
		if (!(stack.getItem() instanceof BlockItem)) {
			return false;
		}
		BlockPos clicked = hit.getBlockPos();
		if (!player.isShiftKeyDown() && opensOrToggles(mc.level, clicked, mc.level.getBlockState(clicked))) {
			return false; // the click uses the block, it does not place anything
		}
		Boolean inside;
		try {
			BlockPos target = new BlockPlaceContext(player, hand, stack, hit).getClickedPos();
			inside = LitematicaBridge.isSchematicBlock(target);
		} catch (LinkageError | RuntimeException e) {
			broken = true; // incompatible Litematica: stop guarding instead of failing on every click
			KhoaTools.LOGGER.error("Schematic Guard could not talk to Litematica, it is now disabled until restart", e);
			return false;
		}
		if (inside == null || inside) {
			return false;
		}
		long now = System.currentTimeMillis();
		if (showMessage() && now - lastMessage > 600) {
			lastMessage = now;
			mc.gui.hud.setOverlayMessage(Component.literal("Schematic Guard: outside the schematic layer, not placed"), false);
		}
		return true;
	}

	/** Blocks that react to a right click (without sneaking) instead of taking the held block. */
	private static boolean opensOrToggles(Level level, BlockPos pos, BlockState state) {
		if (state.getMenuProvider(level, pos) != null) {
			return true;
		}
		Object block = state.getBlock();
		return block instanceof ButtonBlock || block instanceof DoorBlock || block instanceof TrapDoorBlock
			|| block instanceof FenceGateBlock || block instanceof LeverBlock || block instanceof BedBlock
			|| block instanceof NoteBlock || block instanceof DiodeBlock || block instanceof DaylightDetectorBlock
			|| block instanceof SignBlock || block instanceof LecternBlock;
	}
}
