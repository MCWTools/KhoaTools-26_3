package khoa.tools.client.feature.palette;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

import khoa.api.world.KhoaRay;
import khoa.api.world.RayHit;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.palette.BuildingPalette;
import khoa.tools.palette.PaletteStatePayload;

/**
 * Client half of the Building Palette ({@code /khoatools palette give}). With the palette in a hand:
 * <ul>
 * <li>Hold right click on a block (as far as your render distance) and drag to another one: a box grows between them.</li>
 * <li>Release: the box stays and the "Block palette randomizer" screen opens ({@link PaletteScreen}).</li>
 * <li>Shift + right click reopens the screen for the same box (palettes you chose are kept).</li>
 * </ul>
 * Right clicks with the palette never reach the server (no block gets used). Needs KhoaTools on the server to apply.
 */
public final class PaletteClientFeature implements KhoaFeature {
	private static final String ID = "palette";
	private static final int COLOR = 0x55FFAA;

	/** The selection (null = none). */
	static BlockPos min;
	static BlockPos max;
	/** Palettes chosen per source block; kept while the box stays, cleared by a new selection. */
	static final Map<Block, List<Block>> PALETTES = new HashMap<>();

	private static BlockPos anchor;
	private static BlockPos current;
	private static boolean dragging;
	private static boolean wasDown;
	/** How many applies of this selection the server can still undo (it tells us after every apply / undo). */
	static int undoDepth;
	/** Ticks until the open screen rescans its block list (block updates arrive a moment after the server's reply); 0 = none pending. */
	static int rescanTicks;
	private static ClientLevel selectionLevel;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Building Palette";
	}

	@Override
	public String description() {
		return "Client side of /khoatools palette give. Hold right click on a block and drag to another to select a box, release to open the Block palette randomizer "
			+ "(choose a palette per block type, Apply replaces each with a random block of its palette). Shift + right click reopens it. No settings.";
	}

	@Override
	public void onInit() {
		ClientPlayNetworking.registerGlobalReceiver(PaletteStatePayload.TYPE, (payload, context) -> {
			undoDepth = payload.undoDepth();
			rescanTicks = 4;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> holding(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		UseItemCallback.EVENT.register((player, level, hand) -> holding(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			PaletteSelfTest.tick(mc);
			tick(mc);
		});
		LevelRenderEvents.END_EXTRACTION.register(context -> draw(Minecraft.getInstance()));
	}

	static boolean holding(Player player) {
		return BuildingPalette.isPalette(player.getMainHandItem()) || BuildingPalette.isPalette(player.getOffhandItem());
	}

	/** Drops the selection and its palettes. */
	static void clear() {
		min = null;
		max = null;
		anchor = null;
		current = null;
		dragging = false;
		PALETTES.clear();
		undoDepth = 0;
		selectionLevel = null;
	}

	private static BlockPos aim(Minecraft mc) {
		double range = Math.max(2, mc.options.renderDistance().get()) * 16.0;
		RayHit hit = KhoaRay.block(mc.level, mc.player, mc.player.getEyePosition(), mc.player.getLookAngle(), range, false);
		return hit.hit() ? hit.pos() : null;
	}

	private static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			clear();
			wasDown = false;
			return;
		}
		if (selectionLevel != null && selectionLevel != mc.level) {
			clear();
		}
		boolean down = mc.options.keyUse.isDown() && mc.gui.screen() == null && holding(mc.player);
		boolean pressed = down && !wasDown;
		wasDown = down;
		if (pressed) {
			if (mc.player.isShiftKeyDown()) {
				if (min != null) {
					mc.gui.setScreen(new PaletteScreen());
				}
				return;
			}
			BlockPos start = aim(mc);
			if (start != null) {
				anchor = start;
				current = start;
				dragging = true;
				selectionLevel = mc.level;
				setBox(anchor, current);
				PALETTES.clear();
		undoDepth = 0;
			}
			return;
		}
		if (dragging) {
			if (down) {
				BlockPos now = aim(mc);
				if (now != null) {
					current = now;
					setBox(anchor, current);
				}
			} else {
				dragging = false;
				mc.gui.setScreen(new PaletteScreen());
			}
		}
	}

	private static void setBox(BlockPos a, BlockPos b) {
		min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
		max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
	}

	private static void draw(Minecraft mc) {
		if (min == null || mc.player == null || mc.level == null || !(dragging || holding(mc.player))) {
			return;
		}
		AABB box = new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1).inflate(0.004);
		Gizmos.cuboid(box, GizmoStyle.strokeAndFill(0xFF000000 | COLOR, 2.5F, 0x28000000 | COLOR)).setAlwaysOnTop();
	}
}
