package khoa.tools.client.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Colour API for other mods: the average colour of a block's texture.
 *
 * <pre>{@code
 * int rgb = KhoaBlockColor.averageRgb(Blocks.DIRT);              // 0xRRGGBB
 * int outline = KhoaBlockColor.brighten(rgb, 0.4F);               // lighter version, e.g. for an outline
 * OptionalInt maybe = KhoaBlockColor.tryAverageRgb(Blocks.STONE); // empty if no texture could be read
 * }</pre>
 *
 * The texture used is the block's particle texture (the main texture the model picks for it, e.g. the side of a log or grass block).
 * Fully transparent pixels (alpha below 16) are ignored; animated textures use their first frame. Biome tints (grass, leaves, water) are NOT applied,
 * so those blocks give the grey of the raw texture. Results are cached per block state; call from the client thread after resources are loaded
 * (in a world or at least on the title screen).
 */
public final class KhoaBlockColor {
	/** Returned by {@link #averageRgb} when the texture cannot be read. */
	public static final int FALLBACK = 0x808080;
	private static final int MIN_ALPHA = 16;

	private static final Map<BlockState, OptionalInt> CACHE = new HashMap<>();
	private static final Map<BlockState, int[]> FACE_CACHE = new HashMap<>();
	private static final Map<Identifier, OptionalInt> SPRITE_CACHE = new HashMap<>();

	private KhoaBlockColor() {
	}

	/** Forget cached colours (e.g. after a resource pack change). */
	public static synchronized void clearCache() {
		CACHE.clear();
		FACE_CACHE.clear();
		SPRITE_CACHE.clear();
	}

	/** Average RGB (0xRRGGBB) of the block's texture, or {@link #FALLBACK} (grey) when unavailable. */
	public static int averageRgb(Block block) {
		return averageRgb(block.defaultBlockState());
	}

	/** Colour of this exact state: blocks whose texture depends on the state (lit / unlit, variants...) give different colours. */
	public static int averageRgb(BlockState state) {
		return tryAverageRgb(state).orElse(FALLBACK);
	}

	public static OptionalInt tryAverageRgb(Block block) {
		return tryAverageRgb(block.defaultBlockState());
	}

	public static synchronized OptionalInt tryAverageRgb(BlockState state) {
		OptionalInt cached = CACHE.get(state);
		if (cached == null) {
			cached = compute(state);
			CACHE.put(state, cached);
		}
		return cached;
	}

	/** Average RGB of the texture on one face of this state (what the model draws on that side), or the state's main colour when that face has no quad. */
	public static int averageRgb(BlockState state, Direction face) {
		return faceColors(state)[face.get3DDataValue()];
	}

	/** Colours of all six faces, indexed by {@link Direction#get3DDataValue()} (down, up, north, south, west, east). */
	public static synchronized int[] faceColors(BlockState state) {
		int[] cached = FACE_CACHE.get(state);
		if (cached != null) {
			return cached;
		}
		int main = averageRgb(state);
		int[] out = new int[6];
		java.util.List<BlockStateModelPart> parts = new java.util.ArrayList<>();
		try {
			Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state).collectParts(RandomSource.create(42L), parts);
		} catch (RuntimeException e) {
			parts.clear();
		}
		for (Direction d : Direction.values()) {
			int color = main;
			faces:
			for (BlockStateModelPart part : parts) {
				for (BakedQuad quad : part.getQuads(d)) {
					OptionalInt c = spriteColor(quad.materialInfo().sprite());
					if (c.isPresent()) {
						color = c.getAsInt();
						break faces;
					}
				}
			}
			out[d.get3DDataValue()] = color;
		}
		FACE_CACHE.put(state, out);
		return out;
	}

	/** Moves each channel {@code amount} (0..1) of the way to white. 0 = same colour, 1 = white. */
	public static int brighten(int rgb, float amount) {
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		r += Math.round((255 - r) * amount);
		g += Math.round((255 - g) * amount);
		b += Math.round((255 - b) * amount);
		return (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
	}

	private static OptionalInt compute(BlockState state) {
		Minecraft mc = Minecraft.getInstance();
		try {
			return spriteColor(mc.getModelManager().getBlockStateModelSet().getParticleMaterial(state).sprite());
		} catch (RuntimeException e) {
			return OptionalInt.empty();
		}
	}

	private static OptionalInt spriteColor(TextureAtlasSprite sprite) {
		Identifier name = sprite.contents().name();
		OptionalInt known = SPRITE_CACHE.get(name);
		if (known == null) {
			known = readColor(name);
			SPRITE_CACHE.put(name, known);
		}
		return known;
	}

	private static OptionalInt readColor(Identifier name) {
		Minecraft mc = Minecraft.getInstance();
		try {
			Identifier file = Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + ".png");
			Optional<Resource> resource = mc.getResourceManager().getResource(file);
			if (resource.isEmpty()) {
				return OptionalInt.empty();
			}
			try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
				int w = image.getWidth();
				int h = Math.min(image.getHeight(), w); // animated strips: first frame only
				long r = 0;
				long g = 0;
				long b = 0;
				long n = 0;
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++) {
						int argb = image.getPixel(x, y);
						if ((argb >>> 24) < MIN_ALPHA) {
							continue; // transparent: not part of the colour
						}
						r += (argb >> 16) & 0xFF;
						g += (argb >> 8) & 0xFF;
						b += argb & 0xFF;
						n++;
					}
				}
				if (n == 0) {
					return OptionalInt.empty();
				}
				return OptionalInt.of((int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n));
			}
		} catch (IOException | RuntimeException e) {
			return OptionalInt.empty();
		}
	}
}
