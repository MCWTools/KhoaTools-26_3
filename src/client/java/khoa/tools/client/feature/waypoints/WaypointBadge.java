package khoa.tools.client.feature.waypoints;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** The small down arrow with a letter (or a skull) used on the HUD board, in the list and in the editor. */
public final class WaypointBadge {
	/** Xaero's 16 waypoint colours (index = the colour number in its files). */
	public static final int[] XAERO = {
		0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
		0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF };

	public static final int DEATH_RGB = 0xE04848;
	public static final int WIDTH = 13;
	public static final int HEIGHT = 17;

	private static final String[] SKULL = {
		".#####.",
		"#######",
		"#..#..#",
		"#..#..#",
		"#######",
		".#####.",
		".#.#.#." };

	private WaypointBadge() {
	}

	/** Draws the arrow with its tip at (tipX, tipY), scaled around the tip. letter == null draws a skull. */
	public static void draw(GuiGraphicsExtractor g, Font font, float tipX, float tipY, float scale, int rgb, String letter) {
		g.pose().pushMatrix();
		g.pose().translate(tipX, tipY);
		g.pose().scale(scale, scale);
		int left = -WIDTH / 2;
		int top = -HEIGHT;
		shape(g, left, top, 0xFF101010, 1);
		shape(g, left, top, 0xFF000000 | rgb, 0);
		int ink = readable(rgb);
		if (letter == null) {
			skull(g, -3, top + 2, ink);
		} else {
			g.text(font, letter, -font.width(letter) / 2, top + 2, ink, false);
		}
		g.pose().popMatrix();
	}

	private static void shape(GuiGraphicsExtractor g, int left, int top, int color, int grow) {
		g.fill(left - grow, top - grow, left + WIDTH + grow, top + 11, color);
		for (int t = 0; t < 6; t++) {
			int inset = t + 1;
			g.fill(left + inset - grow, top + 11 + t, left + WIDTH - inset + grow, top + 12 + t, color);
		}
		if (grow > 0) {
			g.fill(left + 6 - grow, top + 17, left + 7 + grow, top + 17 + grow, color);
		}
	}

	/** 7x7 pixel skull. */
	public static void skull(GuiGraphicsExtractor g, int x, int y, int color) {
		for (int row = 0; row < SKULL.length; row++) {
			for (int col = 0; col < 7; col++) {
				if (SKULL[row].charAt(col) == '#') {
					g.fill(x + col, y + row, x + col + 1, y + row + 1, color);
				}
			}
		}
	}

	/** White or dark text colour that can be read on this background. */
	public static int readable(int rgb) {
		double lum = 0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF);
		return lum > 150 ? 0xFF202020 : 0xFFFFFFFF;
	}
}
