package khoa.tools.client.gui;

import java.util.List;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.setting.Setting;

/** Open animation of the KhoaTools GUI (settings live in the "GUI" card): top bar fades in, then the cards fly in one after another. */
public final class GuiAnim {
	public static final String SECTION = "gui";
	public static final int MODE_BOTTOM_UP = 0;
	public static final int MODE_OUTSIDE_IN = 1;
	public static final int MIN_LENGTH = 100;
	public static final int MAX_LENGTH = 2000;
	/** Pixels a card travels while it flies in. */
	private static final int FLY_Y = 64;
	private static final int FLY_X = 140;

	private GuiAnim() {
	}

	public static boolean enabled() {
		return KhoaConfig.getBool(SECTION, "animation", true);
	}

	/** Duration of one fade / fly in milliseconds. */
	public static int length() {
		return Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, KhoaConfig.getInt(SECTION, "animationLength", 400)));
	}

	public static int mode() {
		return Math.max(0, Math.min(1, KhoaConfig.getInt(SECTION, "animationMode", MODE_BOTTOM_UP)));
	}

	public static List<Setting> settings() {
		return List.of(
			Setting.bool("animation", "Animation", "Opening the GUI: the top bar fades in, then every card fades in while flying to its place.",
				GuiAnim::enabled, v -> KhoaConfig.put(SECTION, "animation", v)),
			Setting.slider("animationLength", "Animation Length",
				"Milliseconds one fade / fly takes (" + MIN_LENGTH + " - " + MAX_LENGTH + "). The delay between cards is a fraction of it.",
				MIN_LENGTH, MAX_LENGTH, GuiAnim::length, v -> KhoaConfig.put(SECTION, "animationLength", v)),
			Setting.dropdown("animationMode", "Animation Mode",
				"Bottom up: cards fly up from below in snake order (left to right, then right to left...). "
					+ "Outside in: left column slides in from the left, right column from the right (a middle column would fly up); columns run in parallel.",
				List.of("Bottom up", "Outside in"), GuiAnim::mode, v -> KhoaConfig.put(SECTION, "animationMode", v)));
	}

	/** Quad in-out. */
	public static float ease(float t) {
		t = Math.max(0, Math.min(1, t));
		return t < 0.5F ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2;
	}

	/** 0..1 (eased) of the top bar. */
	public static float topBar(long elapsedMs) {
		return enabled() ? ease(elapsedMs / (float) length()) : 1;
	}

	/**
	 * 0..1 (eased) of card {@code index} in a grid with {@code cols} columns. {@code cap} = how many cards fit on screen: cards further
	 * down start with the delay of the last visible one, so a card scrolled into view is not waiting for the whole list.
	 */
	public static float card(int index, int cols, int cap, long elapsedMs) {
		if (!enabled()) {
			return 1;
		}
		int len = length();
		int row = index / cols;
		int col = index % cols;
		int order;
		if (mode() == MODE_OUTSIDE_IN) {
			order = Math.min(row, cap / cols + 1); // each column runs its own top-to-bottom sequence, all in parallel
		} else {
			int snake = row * cols + (row % 2 == 0 ? col : cols - 1 - col);
			order = Math.min(snake, cap);
		}
		// The first card starts while the top bar is still fading in (not after it finished), so there is no dead pause.
		long start = len / 4 + (long) order * Math.max(10, len / 8);
		return ease((elapsedMs - start) / (float) len);
	}

	/** Horizontal offset in px for a card with eased progress {@code e} (negative = left of its place). */
	public static int offsetX(int col, int cols, float e) {
		if (mode() != MODE_OUTSIDE_IN || cols < 2 || (col > 0 && col < cols - 1)) {
			return 0;
		}
		return Math.round((col == 0 ? -1 : 1) * (1 - e) * FLY_X);
	}

	/** Vertical offset in px (positive = below its place). */
	public static int offsetY(int col, int cols, float e) {
		boolean sideColumn = mode() == MODE_OUTSIDE_IN && cols >= 2 && (col == 0 || col == cols - 1);
		return sideColumn ? 0 : Math.round((1 - e) * FLY_Y);
	}

	/** Multiplies the alpha of an ARGB colour by {@code a}. */
	public static int fade(int argb, float a) {
		int alpha = Math.round(((argb >>> 24) & 0xFF) * Math.max(0, Math.min(1, a)));
		return (alpha << 24) | (argb & 0xFFFFFF);
	}
}
