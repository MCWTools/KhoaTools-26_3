package khoa.tools.client.feature.pallet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import khoa.api.picker.KhoaPalettePicker;
import khoa.tools.pallet.PalletApplyPayload;

/**
 * "Block pallet randomizer": one row per block TYPE found in the selected box, with its count and a "Select pallet" button (opens the
 * colour sorted {@link KhoaPalettePicker}, the block itself already inside). Apply sends every type that has a palette to the server.
 * Esc / Cancel only close the screen: the selection and the palettes stay (shift + right click with the pallet reopens it).
 */
public final class PalletScreen extends Screen {
	private static final int ROW_H = 26;
	private static final int BTN_W = 96;
	/** Scanning more blocks than this on the client is refused (it would freeze the game). */
	private static final long MAX_SCAN = 4_000_000L;

	private record Row(Block block, int count) {
	}

	private final List<Row> rows = new ArrayList<>();
	private String note = "";
	private double scroll;
	private int listX;
	private int listY;
	private int listW;
	private int listH;

	public PalletScreen() {
		super(Component.literal("Block pallet randomizer"));
		scan();
	}

	private void scan() {
		BlockPos min = PalletClientFeature.min;
		BlockPos max = PalletClientFeature.max;
		var level = net.minecraft.client.Minecraft.getInstance().level;
		if (min == null || level == null) {
			note = "No selection.";
			return;
		}
		long sx = (long) max.getX() - min.getX() + 1;
		long sy = (long) max.getY() - min.getY() + 1;
		long sz = (long) max.getZ() - min.getZ() + 1;
		if (sx * sy * sz > MAX_SCAN) {
			note = "Selection too big (" + sx + "x" + sy + "x" + sz + "), max " + MAX_SCAN + " blocks.";
			return;
		}
		Map<Block, Integer> counts = new HashMap<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int unloaded = 0;
		for (int x = min.getX(); x <= max.getX(); x++) {
			for (int z = min.getZ(); z <= max.getZ(); z++) {
				if (!level.hasChunkAt(pos.set(x, level.getMinY(), z))) {
					unloaded++;
					continue;
				}
				for (int y = Math.max(min.getY(), level.getMinY()); y <= Math.min(max.getY(), level.getMaxY()); y++) {
					BlockState s = level.getBlockState(pos.set(x, y, z));
					if (!s.isAir()) {
						counts.merge(s.getBlock(), 1, Integer::sum);
					}
				}
			}
		}
		counts.forEach((b, n) -> rows.add(new Row(b, n)));
		rows.sort(Comparator.comparingInt(Row::count).reversed().thenComparing(r -> BuiltInRegistries.BLOCK.getKey(r.block()).toString()));
		note = sx + " x " + sy + " x " + sz + " box, " + rows.size() + " block types" + (unloaded > 0 ? " (" + unloaded + " unloaded columns not counted)" : "");
	}

	@Override
	protected void init() {
		listW = Math.min(this.width - 24, 460);
		listX = (this.width - listW) / 2;
		listY = 44;
		listH = this.height - listY - 34;
		int by = this.height - 26;
		addRenderableWidget(Button.builder(Component.literal("Apply"), b -> apply()).bounds(this.width / 2 - 102, by, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(this.width / 2 + 2, by, 100, 20).build());
		clampScroll();
	}

	private void clampScroll() {
		scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() * ROW_H - listH)));
	}

	private int chosen(Block b) {
		List<Block> p = PalletClientFeature.PALETTES.get(b);
		return p == null ? 0 : p.size();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float partial) {
		g.fill(0, 0, this.width, this.height, 0xC0101014);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
		super.extractRenderState(g, mx, my, partial);
		g.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);
		g.centeredText(this.font, Component.literal(note), this.width / 2, 26, 0xFFAAAAAA);
		g.fill(listX - 2, listY - 2, listX + listW + 2, listY + listH + 2, 0xA0000000);
		g.enableScissor(listX, listY, listX + listW, listY + listH);
		for (int i = 0; i < rows.size(); i++) {
			int y = listY + i * ROW_H - (int) scroll;
			if (y + ROW_H < listY || y > listY + listH) {
				continue;
			}
			Row r = rows.get(i);
			int n = chosen(r.block());
			g.fill(listX, y, listX + listW, y + ROW_H - 2, n > 0 ? 0xA02E6E9E : 0x90202020);
			g.item(new ItemStack(r.block().asItem()), listX + 5, y + 4);
			g.text(this.font, this.font.plainSubstrByWidth(r.block().getName().getString(), listW - BTN_W - 90), listX + 28, y + 5, 0xFFFFFFFF);
			g.text(this.font, r.count() + " blocks", listX + 28, y + 15, 0xFFAAAAAA);
			int bx = listX + listW - BTN_W - 4;
			boolean over = mx >= bx && mx < bx + BTN_W && my >= y + 2 && my < y + ROW_H - 4 && my >= listY && my < listY + listH;
			g.fill(bx, y + 2, bx + BTN_W, y + ROW_H - 4, over ? 0xFF4A6A4A : 0xFF334433);
			g.outline(bx, y + 2, BTN_W, ROW_H - 6, 0xFF668866);
			g.centeredText(this.font, Component.literal(n > 0 ? "Pallet (" + n + ")" : "Select pallet"), bx + BTN_W / 2, y + 8, 0xFFFFFFFF);
		}
		g.disableScissor();
		if (rows.isEmpty()) {
			g.centeredText(this.font, Component.literal("No blocks in the selection."), this.width / 2, listY + 10, 0xFFAAAAAA);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		double mx = event.x();
		double my = event.y();
		int bx = listX + listW - BTN_W - 4;
		if (mx >= bx && mx < bx + BTN_W && my >= listY && my < listY + listH) {
			int i = (int) ((my - listY + scroll) / ROW_H);
			if (i >= 0 && i < rows.size()) {
				openPicker(rows.get(i).block());
				return true;
			}
		}
		return false;
	}

	private void openPicker(Block block) {
		List<Block> start = PalletClientFeature.PALETTES.get(block);
		KhoaPalettePicker.open(this, "Palette for " + block.getName().getString(), start == null ? List.of(block) : start, picked -> {
			if (picked.isEmpty()) {
				PalletClientFeature.PALETTES.remove(block);
			} else {
				PalletClientFeature.PALETTES.put(block, picked);
			}
		});
	}

	@Override
	public boolean mouseScrolled(double x, double y, double sx, double sy) {
		scroll -= sy * ROW_H;
		clampScroll();
		return true;
	}

	private void apply() {
		List<PalletApplyPayload.Entry> entries = new ArrayList<>();
		for (Row r : rows) {
			List<Block> palette = PalletClientFeature.PALETTES.get(r.block());
			if (palette == null || palette.isEmpty() || (palette.size() == 1 && palette.get(0) == r.block())) {
				continue; // no palette, or a palette of itself only: nothing to change
			}
			List<String> ids = new ArrayList<>();
			for (Block b : palette) {
				ids.add(BuiltInRegistries.BLOCK.getKey(b).toString());
			}
			entries.add(new PalletApplyPayload.Entry(BuiltInRegistries.BLOCK.getKey(r.block()).toString(), ids));
		}
		if (entries.isEmpty()) {
			note = "Choose a palette for at least one block type first (the block alone does nothing).";
			return;
		}
		if (!ClientPlayNetworking.canSend(PalletApplyPayload.TYPE)) {
			note = "The server does not have KhoaTools: cannot apply.";
			return;
		}
		ClientPlayNetworking.send(new PalletApplyPayload(PalletClientFeature.min, PalletClientFeature.max, entries));
		onClose(); // selection and palettes stay
	}

	/** Self test: the Apply handler. */
	public void pressApply() {
		apply();
	}

	/** Self test: opens the picker of the first row with this block. */
	public void pickFor(Block b) {
		openPicker(b);
	}

	public String note() {
		return note;
	}

	public int rowCount() {
		return rows.size();
	}
}
