package khoa.tools.client.feature.blocktext;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a text into rectangles of font pixels on a square grid (the face of the block). Words wrap as whole words; the text is centred.
 * Auto scale picks the biggest letters that still fit (few letters = big, many letters = small, down to {@link #MAX_COLS} pixels per line).
 * Pure Java apart from {@link PixelFont}.
 */
final class BlockTextLayout {
	/** Grid width in font pixels for the biggest letters. */
	static final int MIN_COLS = 30;
	/** Grid width for the smallest letters: about 20 normal letters per line. */
	static final int MAX_COLS = 120;
	static final int MAX_LINES = 10;

	/** Grid size in font pixels (the face is cols x cols). */
	final int cols;
	/** Rectangles {x0, y0, x1, y1} (end exclusive) in grid pixels, origin top left. Outline rectangles may reach 1 pixel outside the grid. */
	final List<int[]> fill;
	final List<int[]> outline;

	private BlockTextLayout(int cols, List<int[]> fill, List<int[]> outline) {
		this.cols = cols;
		this.fill = fill;
		this.outline = outline;
	}

	static String key(BlockText t) {
		return t.font + "|" + t.wrapWords + "|" + t.autoScale + "|" + t.scale + "|" + t.outline + "|" + t.text;
	}

	static BlockTextLayout build(BlockText t) {
		int font = t.font;
		int lineH = PixelFont.lineHeight(font);
		int auto = MAX_COLS;
		boolean words = t.wrapWords;
		int longest = 0; // a word is only cut into letters when it is wider than a line at the smallest size
		for (String word : t.text.trim().split("\\s+")) {
			longest = words ? Math.max(longest, width(word, font)) : 0;
		}
		for (int c = Math.max(MIN_COLS, longest); c <= MAX_COLS; c++) {
			List<String> lines = wrap(t.text, font, c, words);
			if (lines.size() <= MAX_LINES && lines.size() * lineH - 1 <= c) {
				auto = c;
				break;
			}
		}
		int cols = auto;
		if (!t.autoScale) {
			int scale = Math.max(10, Math.min(100, t.scale));
			cols = Math.max(auto, MIN_COLS + (int) Math.round((MAX_COLS - MIN_COLS) * (100 - scale) / 100.0));
		}
		cols = Math.min(MAX_COLS, cols);
		List<String> lines = wrap(t.text, font, cols, words);
		while (lines.size() > MAX_LINES || (lines.size() > 1 && lines.size() * lineH - 1 > cols)) {
			lines.remove(lines.size() - 1);
		}
		int pad = 1;
		int size = cols + 2 * pad;
		boolean[][] grid = new boolean[size][size];
		int blockH = lines.size() * lineH - 1;
		int y0 = Math.max(0, (cols - blockH) / 2);
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			int x = Math.max(0, (cols - width(line, font)) / 2);
			for (int k = 0; k < line.length(); k++) {
				PixelFont.Glyph g = PixelFont.glyph(font, line.charAt(k));
				for (int r = 0; r < g.bits().length; r++) {
					for (int q = 0; q < g.bits()[r].length; q++) {
						int gx = x + q;
						int gy = y0 + i * lineH + r;
						if (g.bits()[r][q] && gx < cols && gy < cols) {
							grid[gy + pad][gx + pad] = true;
						}
					}
				}
				x += g.advance();
			}
		}
		List<int[]> fill = merge(grid, pad);
		List<int[]> outline = List.of();
		if (t.outline) {
			boolean[][] big = new boolean[size][size];
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					if (grid[y][x]) {
						continue;
					}
					boolean near = false;
					for (int dy = -1; dy <= 1 && !near; dy++) {
						for (int dx = -1; dx <= 1; dx++) {
							int yy = y + dy;
							int xx = x + dx;
							if (yy >= 0 && xx >= 0 && yy < size && xx < size && grid[yy][xx]) {
								near = true;
								break;
							}
						}
					}
					big[y][x] = near;
				}
			}
			outline = merge(big, pad);
		}
		return new BlockTextLayout(cols, fill, outline);
	}

	static int width(String line, int font) {
		int w = 0;
		for (int i = 0; i < line.length(); i++) {
			w += PixelFont.glyph(font, line.charAt(i)).advance();
		}
		return Math.max(0, w - 1);
	}

	/** Splits at new lines, then wraps whole words; a word longer than a line is cut. */
	static List<String> wrap(String text, int font, int maxWidth, boolean words) {
		List<String> out = new ArrayList<>();
		if (!words) { // fill every line to the end, break at any letter
			for (String para : text.replace("\r", "").split("\n", -1)) {
				String cur = "";
				for (int i = 0; i < para.length(); i++) {
					String next = cur + para.charAt(i);
					if (!cur.isEmpty() && width(next, font) > maxWidth) {
						out.add(cur);
						cur = String.valueOf(para.charAt(i));
					} else {
						cur = next;
					}
				}
				out.add(cur);
			}
			while (out.size() > 1 && out.get(out.size() - 1).isBlank()) {
				out.remove(out.size() - 1);
			}
			return out;
		}
		for (String para : text.replace("\r", "").split("\n", -1)) {
			String cur = "";
			for (String word : para.split(" ", -1)) {
				while (width(word, font) > maxWidth && word.length() > 1) { // too long for any line: cut it
					int n = word.length() - 1;
					while (n > 1 && width(word.substring(0, n), font) > maxWidth) {
						n--;
					}
					if (!cur.isEmpty()) {
						out.add(cur);
						cur = "";
					}
					out.add(word.substring(0, n));
					word = word.substring(n);
				}
				if (cur.isEmpty()) {
					cur = word;
				} else if (width(cur + " " + word, font) <= maxWidth) {
					cur = cur + " " + word;
				} else {
					out.add(cur);
					cur = word;
				}
			}
			out.add(cur);
		}
		while (out.size() > 1 && out.get(out.size() - 1).isBlank()) {
			out.remove(out.size() - 1);
		}
		return out;
	}

	/** Greedy rectangles over the set pixels; coordinates are shifted back by the padding. */
	private static List<int[]> merge(boolean[][] g, int pad) {
		int h = g.length;
		int w = g[0].length;
		boolean[][] left = new boolean[h][w];
		for (int y = 0; y < h; y++) {
			left[y] = g[y].clone();
		}
		List<int[]> out = new ArrayList<>();
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (!left[y][x]) {
					continue;
				}
				int x1 = x;
				while (x1 < w && left[y][x1]) {
					x1++;
				}
				int y1 = y + 1;
				grow:
				while (y1 < h) {
					for (int k = x; k < x1; k++) {
						if (!left[y1][k]) {
							break grow;
						}
					}
					y1++;
				}
				for (int yy = y; yy < y1; yy++) {
					for (int xx = x; xx < x1; xx++) {
						left[yy][xx] = false;
					}
				}
				out.add(new int[] { x - pad, y - pad, x1 - pad, y1 - pad });
			}
		}
		return out;
	}
}
