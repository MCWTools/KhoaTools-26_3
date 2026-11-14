package khoa.tools.client.feature.blocktext;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.Optional;
import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import khoa.tools.KhoaTools;

/**
 * The square Minecraft letters as pixels. The glyphs are read once from the game's own font texture (textures/font/ascii.png, 16 x 16 cells of 8 x 8),
 * so the letters match the normal Minecraft font. Four looks are made from them: normal, bold, wide and tall.
 */
final class PixelFont {
	static final String[] NAMES = { "Normal", "Bold", "Wide", "Tall" };
	/** Height of one line in font pixels (8 rows + 1 empty row). */
	static final int LINE = 9;

	/** One letter: bits[row][column], advance = columns used + 1 gap. */
	record Glyph(boolean[][] bits, int advance) {
	}

	private static BufferedImage image;
	private static boolean tried;
	private static final Glyph[][] CACHE = new Glyph[NAMES.length][128];

	private PixelFont() {
	}

	private static BufferedImage image() {
		if (!tried) {
			tried = true;
			try {
				// the game's own file first, so a resource pack with another font cannot change the look
				try (InputStream in = Minecraft.class.getResourceAsStream("/assets/minecraft/textures/font/ascii.png")) {
					if (in != null) {
						image = ImageIO.read(in);
					}
				}
				if (image != null) {
					return image;
				}
				Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(Identifier.parse("minecraft:textures/font/ascii.png"));
				if (res.isPresent()) {
					try (InputStream in = res.get().open()) {
						image = ImageIO.read(in);
					}
				}
			} catch (Exception e) {
				KhoaTools.LOGGER.warn("Block Text: could not read the Minecraft font texture, using boxes instead", e);
			}
		}
		return image;
	}

	/** Letters the pixel font can draw: accents are removed (a with a hat becomes a), anything else unknown becomes ?. */
	static char simplify(char c) {
		if (c == 'đ') {
			return 'd';
		}
		if (c == 'Đ') {
			return 'D';
		}
		if (c < 128) {
			return c;
		}
		String n = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
		char base = n.charAt(0);
		return base < 128 ? base : '?';
	}

	static Glyph glyph(int font, char raw) {
		char c = simplify(raw);
		if (c < 32 || c >= 127) {
			c = '?';
		}
		int f = Math.max(0, Math.min(NAMES.length - 1, font));
		Glyph g = CACHE[f][c];
		if (g == null) {
			g = transform(f, base(c));
			CACHE[f][c] = g;
		}
		return g;
	}

	private static Glyph base(char c) {
		if (c == ' ') {
			return new Glyph(new boolean[8][3], 4);
		}
		BufferedImage img = image();
		if (img == null || img.getWidth() < 128 || img.getHeight() < 128) {
			boolean[][] box = new boolean[8][5];
			for (int r = 0; r < 7; r++) {
				for (int k = 0; k < 5; k++) {
					box[r][k] = r == 0 || r == 6 || k == 0 || k == 4;
				}
			}
			return new Glyph(box, 6);
		}
		int cell = img.getWidth() / 16;
		int col = c % 16;
		int row = c / 16;
		int width = 0;
		boolean[][] bits = new boolean[8][8];
		for (int y = 0; y < 8; y++) {
			for (int x = 0; x < 8; x++) {
				int px = col * cell + x * cell / 8;
				int py = row * cell + y * cell / 8;
				boolean on = (img.getRGB(px, py) >>> 24) > 40;
				bits[y][x] = on;
				if (on) {
					width = Math.max(width, x + 1);
				}
			}
		}
		boolean[][] cut = new boolean[8][width];
		for (int y = 0; y < 8; y++) {
			System.arraycopy(bits[y], 0, cut[y], 0, width);
		}
		return new Glyph(cut, width + 1);
	}

	private static Glyph transform(int font, Glyph g) {
		boolean[][] b = g.bits();
		int w = b[0].length;
		if (font == 1) { // bold: every pixel also fills the one to its right
			boolean[][] out = new boolean[8][w + 1];
			for (int y = 0; y < 8; y++) {
				for (int x = 0; x < w; x++) {
					if (b[y][x]) {
						out[y][x] = true;
						out[y][x + 1] = true;
					}
				}
			}
			return new Glyph(out, g.advance() + 1);
		}
		if (font == 2) { // wide: every column twice
			boolean[][] out = new boolean[8][w * 2];
			for (int y = 0; y < 8; y++) {
				for (int x = 0; x < w; x++) {
					out[y][x * 2] = b[y][x];
					out[y][x * 2 + 1] = b[y][x];
				}
			}
			return new Glyph(out, g.advance() * 2);
		}
		if (font == 3) { // tall: every row twice
			boolean[][] out = new boolean[16][w];
			for (int y = 0; y < 8; y++) {
				out[y * 2] = b[y].clone();
				out[y * 2 + 1] = b[y].clone();
			}
			return new Glyph(out, g.advance());
		}
		return g;
	}

	/** Height of one text line in font pixels for this font. */
	static int lineHeight(int font) {
		return font == 3 ? LINE * 2 : LINE;
	}
}
