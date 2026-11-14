package khoa.tools.client.feature.illegal.spawnerfinder;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** The look shared by the picker icon and the in-world overlay: dark grey tile + a mob coloured square (75 % of the tile) in the middle; trial spawners have a copper top half. */
public final class SpawnerLook {
	public static final int DARK_GRAY = 0xFF3C3C3C;
	public static final int COPPER = 0xFFD9783A;
	public static final int OUTLINE = 0xFF111111;
	/** Size of the coloured square relative to the tile. */
	public static final float CENTER_SCALE = 0.75F;

	private SpawnerLook() {
	}

	/** Draws the icon at (x, y), size x size pixels. */
	public static void drawIcon(GuiGraphicsExtractor g, int x, int y, int size, boolean trial, int color) {
		g.fill(x, y, x + size, y + size, OUTLINE);
		int in = x + 1;
		int top = y + 1;
		int w = size - 2;
		if (trial) {
			int half = w / 2;
			g.fill(in, top, in + w, top + half, COPPER);
			g.fill(in, top + half, in + w, top + w, DARK_GRAY);
		} else {
			g.fill(in, top, in + w, top + w, DARK_GRAY);
		}
		int s = Math.max(2, Math.round(w * CENTER_SCALE));
		int cx = in + (w - s) / 2;
		int cy = top + (w - s) / 2;
		g.fill(cx, cy, cx + s, cy + s, 0xFF000000 | color);
	}
}
