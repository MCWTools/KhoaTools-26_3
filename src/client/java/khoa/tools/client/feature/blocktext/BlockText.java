package khoa.tools.client.feature.blocktext;

import java.util.UUID;

/** One text written on a side of a block. Public fields because it is stored with Gson (BlockText.json). */
public final class BlockText {
	public String id = UUID.randomUUID().toString();
	public String dim = "minecraft:overworld";
	public int x;
	public int y;
	public int z;
	/** Side of the block: north, south, east, west, up, down. */
	public String face = "north";
	public String text = "";
	/** 0 normal, 1 bold, 2 wide, 3 tall (see {@link PixelFont#NAMES}). */
	public int font;
	public int rgb = 0xFFFFFF;
	public boolean outline = true;
	public int outlineRgb = 0x000000;
	public boolean autoScale = true;
	/** true = lines break between words (a word is only cut when it cannot fit); false = lines break at any letter. */
	public boolean wrapWords = true;
	/** Only used when autoScale is off: 100 = as big as fits, lower = smaller. */
	public int scale = 100;

	/** Cached drawing data; rebuilt when the text or the layout settings change. */
	transient BlockTextLayout layout;
	transient String layoutKey;

	public BlockText copy() {
		BlockText c = new BlockText();
		c.id = id;
		c.dim = dim;
		c.x = x;
		c.y = y;
		c.z = z;
		c.face = face;
		c.text = text;
		c.font = font;
		c.rgb = rgb;
		c.outline = outline;
		c.outlineRgb = outlineRgb;
		c.autoScale = autoScale;
		c.wrapWords = wrapWords;
		c.scale = scale;
		return c;
	}

	public void set(BlockText o) {
		text = o.text;
		font = o.font;
		rgb = o.rgb;
		outline = o.outline;
		outlineRgb = o.outlineRgb;
		autoScale = o.autoScale;
		wrapWords = o.wrapWords;
		scale = o.scale;
		layout = null;
	}
}
