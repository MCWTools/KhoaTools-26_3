package khoa.tools.client.feature.pallet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import khoa.api.picker.BlockColorGroups;
import khoa.api.picker.ColorGroup;
import khoa.api.picker.KhoaPalettePicker;
import khoa.tools.pallet.BuildingPallet;

/**
 * In-process test of the Building Pallet (dev only: {@code gradlew runClient -Ppallettest}). Drives the real path: give command, held right
 * click + aim to select, the screen, the colour sorted picker, Apply to the integrated server, shift + right click to reopen.
 * Writes run/config/khoatools-pallettest.log and run/screenshots/pallettest-*.png, then closes the game.
 */
final class PalletSelfTest {
	private record Step(String name, Runnable action, Supplier<String> check, int delay) {
	}

	private static final boolean AUTO = System.getProperty("khoatools.pallettest") != null;
	private static final List<Step> STEPS = new ArrayList<>();
	private static boolean started;
	private static int index;
	private static int wait;
	private static boolean acted;
	private static int worldTicks;
	private static int passes;
	private static int failures;
	private static Path log;
	private static BlockPos origin;

	private PalletSelfTest() {
	}

	static void tick(Minecraft mc) {
		if (!AUTO) {
			return;
		}
		if (!started) {
			if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen) {
				mc.gui.setScreen(null);
			}
			worldTicks = mc.player != null && mc.level != null && mc.gui.screen() == null ? worldTicks + 1 : 0;
			if (worldTicks > 100) {
				started = true;
				log = FabricLoader.getInstance().getConfigDir().resolve("khoatools-pallettest.log");
				try {
					Files.writeString(log, "", StandardCharsets.UTF_8);
				} catch (IOException ignored) {
					// best effort
				}
				build(mc);
				write("started, " + STEPS.size() + " steps");
			}
			return;
		}
		if (wait > 0) {
			wait--;
			return;
		}
		if (index >= STEPS.size()) {
			write("DONE: " + passes + " passed, " + failures + " failed");
			started = false;
			worldTicks = Integer.MIN_VALUE;
			mc.stop();
			return;
		}
		Step s = STEPS.get(index);
		if (!acted) {
			write("step " + (index + 1) + ": " + s.name());
			try {
				s.action().run();
			} catch (Throwable t) {
				write("  FAIL exception " + t);
				for (StackTraceElement e : t.getStackTrace()) {
					write("    at " + e);
					if (e.getClassName().startsWith("java.")) {
						break;
					}
				}
				failures++;
				index++;
				return;
			}
			acted = true;
			wait = s.delay();
			return;
		}
		acted = false;
		index++;
		String result;
		try {
			result = s.check() == null ? null : s.check().get();
		} catch (Throwable t) {
			result = "exception " + t;
		}
		if (result == null) {
			passes++;
			write("  ok");
		} else {
			failures++;
			write("  FAIL " + result);
		}
	}

	private static void write(String line) {
		System.out.println("[KhoaTools pallettest] " + line);
		if (log != null) {
			try {
				Files.writeString(log, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException ignored) {
				// best effort
			}
		}
	}

	private static void add(String name, Runnable action, Supplier<String> check, int delay) {
		STEPS.add(new Step(name, action, check, delay));
	}

	private static String expect(boolean ok, String message) {
		return ok ? null : message;
	}

	private static void cmd(Minecraft mc, String c) {
		mc.player.connection.sendCommand(c);
	}

	private static void lookAt(Minecraft mc, Vec3 target) {
		Vec3 d = target.subtract(mc.player.getEyePosition());
		mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
		mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, name + ".png", mc.gameRenderer.mainRenderTarget(), 1, c -> write("  screenshot " + name));
	}

	private static void click(net.minecraft.client.gui.screens.Screen s, double x, double y) {
		s.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
		s.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
	}

	private static int count(Minecraft mc, Block block) {
		int n = 0;
		for (int x = 2; x <= 7; x++) {
			for (int z = 2; z <= 7; z++) {
				if (mc.level.getBlockState(origin.offset(x, -1, z)).is(block)) {
					n++;
				}
			}
		}
		return n;
	}

	private static Block blk(String path) {
		Block b = BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(path));
		if (b == Blocks.AIR) {
			throw new IllegalStateException("no block " + path);
		}
		return b;
	}

	private static Vec3 topCenter(int dx, int dz) {
		return Vec3.atCenterOf(origin.offset(dx, -1, dz)).add(0, 0.5, 0);
	}

	private static void build(Minecraft mc) {
		// 0: palette picker list (pure data)
		add("colour groups: full cubes only, white first, uncategorized last", () -> {
		}, () -> {
			List<Block> all = BlockColorGroups.sorted();
			for (Block b : all) {
				String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
				if (id.contains("slab") || id.contains("stairs") || id.contains("fence") || id.endsWith("_wall") || id.contains("door") || id.contains("button")) {
					return "non cube in list: " + id;
				}
			}
			StringBuilder sb = new StringBuilder("  total " + all.size() + "\n");
			for (ColorGroup g : ColorGroup.values()) {
				List<Block> list = BlockColorGroups.of(g);
				sb.append("  ").append(g.label()).append(" ").append(list.size()).append(": ");
				for (int i = 0; i < Math.min(8, list.size()); i++) {
					sb.append(BuiltInRegistries.BLOCK.getKey(list.get(i)).getPath()).append(i < 7 ? ", " : "");
				}
				sb.append("\n");
			}
			write(sb.toString().stripTrailing());
			Block white = blk("white_wool");
			Block red = blk("red_concrete");
			if (BlockColorGroups.groupOf(white) != ColorGroup.WHITE || BlockColorGroups.groupOf(red) != ColorGroup.RED) {
				return "dyed blocks in the wrong group: " + BlockColorGroups.groupOf(white) + " / " + BlockColorGroups.groupOf(red);
			}
			return expect(BlockColorGroups.groupOf(Blocks.STONE) != ColorGroup.UNCATEGORIZED && all.indexOf(white) < all.indexOf(red)
				&& all.indexOf(red) < all.indexOf(Blocks.COAL_ORE) && !all.contains(blk("lilac")), "order wrong");
		}, 1);

		add("give the pallet", () -> {
			origin = mc.player.blockPosition();
			cmd(mc, "khoatools pallet give @s");
		}, () -> {
			for (int i = 0; i < 9; i++) {
				if (BuildingPallet.isPallet(mc.player.getInventory().getItem(i))) {
					return null;
				}
			}
			return "pallet not in the hotbar";
		}, 20);

		add("hold it and build a 6x6 floor", () -> {
			for (int i = 0; i < 9; i++) {
				if (BuildingPallet.isPallet(mc.player.getInventory().getItem(i))) {
					mc.player.getInventory().setSelectedSlot(i);
				}
			}
			cmd(mc, "fill " + origin.getX() + " " + origin.getY() + " " + origin.getZ() + " " + (origin.getX() + 9) + " " + (origin.getY() + 5) + " " + (origin.getZ() + 9) + " air");
			cmd(mc, "fill " + (origin.getX() + 2) + " " + (origin.getY() - 1) + " " + (origin.getZ() + 2) + " " + (origin.getX() + 7) + " " + (origin.getY() - 1) + " " + (origin.getZ() + 7) + " stone_bricks");
			cmd(mc, "setblock " + (origin.getX() + 3) + " " + (origin.getY() - 1) + " " + (origin.getZ() + 3) + " stone");
			cmd(mc, "setblock " + (origin.getX() + 5) + " " + (origin.getY() - 1) + " " + (origin.getZ() + 4) + " stone");
			cmd(mc, "setblock " + (origin.getX() + 6) + " " + (origin.getY() - 1) + " " + (origin.getZ() + 6) + " stone");
		}, () -> expect(BuildingPallet.isPallet(mc.player.getMainHandItem()) && count(mc, Blocks.STONE_BRICKS) == 33 && count(mc, Blocks.STONE) == 3,
			"setup: stone bricks " + count(mc, Blocks.STONE_BRICKS) + ", stone " + count(mc, Blocks.STONE)), 30);

		add("right click on corner A", () -> {
			lookAt(mc, topCenter(2, 2));
			mc.options.keyUse.setDown(true);
		}, () -> expect(PalletClientFeature.min != null, "no selection started"), 4);

		add("drag to corner B", () -> lookAt(mc, topCenter(7, 7)), () -> {
			BlockPos min = PalletClientFeature.min;
			BlockPos max = PalletClientFeature.max;
			return expect(min != null && max.getX() - min.getX() == 5 && max.getZ() - min.getZ() == 5, "box " + min + " .. " + max);
		}, 4);

		add("screenshot of the selection box", () -> shot(mc, "pallettest-1-box"), null, 10);

		add("release: the randomizer opens, 2 block types", () -> mc.options.keyUse.setDown(false), () -> {
			if (!(mc.gui.screen() instanceof PalletScreen s)) {
				return "screen is " + mc.gui.screen();
			}
			return expect(s.rowCount() == 2, "rows " + s.rowCount() + " note: " + s.note());
		}, 6);

		add("screenshot of the randomizer", () -> shot(mc, "pallettest-2-screen"), null, 10);

		add("Select pallet on stone bricks: picker starts with the block itself", () -> ((PalletScreen) mc.gui.screen()).pickFor(Blocks.STONE_BRICKS), () -> {
			if (!(mc.gui.screen() instanceof KhoaPalettePicker p)) {
				return "picker not open: " + mc.gui.screen();
			}
			return expect(p.palette().equals(List.of(Blocks.STONE_BRICKS)), "palette " + p.palette());
		}, 4);

		add("pick mossy stone bricks and cobblestone", () -> {
			KhoaPalettePicker p = (KhoaPalettePicker) mc.gui.screen();
			p.showGroup(null);
			for (Block b : List.of(Blocks.MOSSY_STONE_BRICKS, Blocks.COBBLESTONE)) {
				p.reveal(b);
				double[] c = p.cardCenter(b);
				if (c == null) {
					throw new IllegalStateException(b + " not in the picker");
				}
				click(p, c[0], c[1]);
			}
		}, () -> {
			KhoaPalettePicker p = (KhoaPalettePicker) mc.gui.screen();
			return expect(p.palette().containsAll(List.of(Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.COBBLESTONE)) && p.palette().size() == 3, "palette " + p.palette());
		}, 4);

		add("screenshot of the picker", () -> {
			((KhoaPalettePicker) mc.gui.screen()).showGroup(ColorGroup.GRAY);
			shot(mc, "pallettest-3-picker");
		}, null, 10);

		add("Done: the palette is stored", () -> ((KhoaPalettePicker) mc.gui.screen()).pressDone(), () -> expect(
			mc.gui.screen() instanceof PalletScreen && PalletClientFeature.PALETTES.get(Blocks.STONE_BRICKS) != null && PalletClientFeature.PALETTES.get(Blocks.STONE_BRICKS).size() == 3,
			"screen " + mc.gui.screen() + " palettes " + PalletClientFeature.PALETTES), 4);

		add("Apply", () -> ((PalletScreen) mc.gui.screen()).pressApply(), () -> expect(mc.gui.screen() == null, "screen still open"), 40);

		add("blocks randomised, stone untouched", () -> {
		}, () -> {
			int bricks = count(mc, Blocks.STONE_BRICKS);
			int mossy = count(mc, Blocks.MOSSY_STONE_BRICKS);
			int cobble = count(mc, Blocks.COBBLESTONE);
			int stone = count(mc, Blocks.STONE);
			write("  bricks " + bricks + " mossy " + mossy + " cobble " + cobble + " stone " + stone);
			return expect(bricks + mossy + cobble == 33 && stone == 3 && mossy > 0 && cobble > 0 && bricks > 0, "unexpected mix");
		}, 1);

		add("selection and palettes survived Apply", () -> {
		}, () -> expect(PalletClientFeature.min != null && PalletClientFeature.PALETTES.size() == 1, "selection cleared " + PalletClientFeature.PALETTES), 1);

		add("shift + right click reopens the screen", () -> {
			mc.player.input.keyPresses = new Input(false, false, false, false, false, true, false);
			mc.options.keyUse.setDown(true);
		}, () -> expect(mc.gui.screen() instanceof PalletScreen, "screen " + mc.gui.screen()), 4);

		add("Esc closes only the screen", () -> {
			mc.options.keyUse.setDown(false);
			mc.player.input.keyPresses = new Input(false, false, false, false, false, false, false);
			mc.gui.screen().onClose();
		}, () -> expect(mc.gui.screen() == null && PalletClientFeature.PALETTES.size() == 1 && PalletClientFeature.min != null, "state lost"), 4);

		add("tidy up", () -> {
			cmd(mc, "fill " + (origin.getX() + 2) + " " + (origin.getY() - 1) + " " + (origin.getZ() + 2) + " " + (origin.getX() + 7) + " " + (origin.getY() - 1) + " " + (origin.getZ() + 7) + " grass_block");
			PalletClientFeature.clear();
		}, null, 10);
	}

}
