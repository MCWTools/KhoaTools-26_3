package khoa.tools.client.gui.picker;

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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import khoa.tools.client.feature.illegal.blockfinder.BlockFinderFeature;
import khoa.tools.setting.PickAdvanced;
import khoa.tools.setting.Setting;

/**
 * In-process test of the picker categories, "Advanced Setting" and Block Finder (dev only: {@code gradlew runClient -Ppickertest}).
 * Delivers real click events to the screens, places ores with commands and reads what Block Finder marks.
 * Writes run/config/khoatools-pickertest.log and run/screenshots/pickertest-*.png, restores the picks and closes the game.
 */
public final class PickerSelfTest {
	private record Step(String name, Runnable action, Supplier<String> check, int delay) {
	}

	private static final boolean AUTO = System.getProperty("khoatools.pickertest") != null;
	private static final String DIAMOND = "block:minecraft:diamond_ore";
	private static final String GOLD = "block:minecraft:gold_ore";
	private static final int SHIFT = 1;
	private static final List<Step> STEPS = new ArrayList<>();
	private static boolean started;
	private static boolean acted;
	private static int index;
	private static int wait;
	private static int worldTicks;
	private static int passes;
	private static int failures;
	private static Path log;
	private static BlockPos low;
	private static BlockPos high;
	private static BlockPos gold;

	private static BlockFinderFeature finder;
	private static Setting picks;
	private static List<String> oldPicks;

	private PickerSelfTest() {
	}

	public static void tick(Minecraft mc) {
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
				log = FabricLoader.getInstance().getConfigDir().resolve("khoatools-pickertest.log");
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
		System.out.println("[KhoaTools pickertest] " + line);
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

	private static void click(Screen s, double[] at, boolean doubleClick, int modifiers) {
		MouseButtonEvent event = new MouseButtonEvent(at[0], at[1], new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, modifiers));
		s.mouseClicked(event, doubleClick);
		s.mouseReleased(event);
	}

	/** Two clicks on a card, the second one flagged as a double click. */
	private static void doubleClick(Minecraft mc, boolean chosenSide, String key, int modifiers) {
		ItemPickerScreen s = (ItemPickerScreen) mc.gui.screen();
		double[] at = s.reveal(chosenSide, key);
		if (at == null) {
			throw new IllegalStateException(key + " is not in the " + (chosenSide ? "picked" : "left") + " list");
		}
		click(s, at, false, modifiers);
		click(mc.gui.screen(), at, true, modifiers);
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, name + ".png", mc.gameRenderer.mainRenderTarget(), 1, c -> write("  screenshot " + name));
	}

	private static void setblock(Minecraft mc, BlockPos p, String block) {
		mc.player.connection.sendCommand("setblock " + p.getX() + " " + p.getY() + " " + p.getZ() + " " + block);
	}

	private static Setting setting(String key) {
		return finder.settings().stream().filter(s -> s.key.equals(key)).findFirst().orElseThrow();
	}

	private static void build(Minecraft mc) {
		finder = new BlockFinderFeature();
		picks = setting("blocks");
		add("setup: three ores in front of the player, nothing picked", () -> {
			oldPicks = picks.getList();
			picks.setList(List.of());
			BlockPos feet = mc.player.blockPosition();
			low = feet.offset(-1, 1, 4);
			high = feet.offset(-1, 4, 4);
			gold = feet.offset(2, 1, 4);
			mc.player.setYRot(0);
			mc.player.setXRot(-10);
			mc.player.connection.sendCommand("fill " + (feet.getX() - 4) + " " + feet.getY() + " " + (feet.getZ() + 2) + " " + (feet.getX() + 4) + " "
				+ (feet.getY() + 6) + " " + (feet.getZ() + 7) + " air");
			setblock(mc, low, "diamond_ore");
			setblock(mc, high, "diamond_ore");
			setblock(mc, gold, "gold_ore");
		}, () -> expect(mc.level.getBlockState(low).is(Blocks.DIAMOND_ORE) && mc.level.getBlockState(high).is(Blocks.DIAMOND_ORE)
			&& mc.level.getBlockState(gold).is(Blocks.GOLD_ORE), "ores not placed (cheats off?)"), 30);

		add("open the Block Finder picker, press Ore Blocks", () -> {
			mc.gui.setScreen(new ItemPickerScreen(null, picks));
		}, null, 5);
		add("Ore Blocks lists only ores", () -> {
			ItemPickerScreen s = (ItemPickerScreen) mc.gui.screen();
			int[] b = s.categoryButtons().get(1);
			click(s, new double[] { b[0] + 4, b[1] + 4 }, false, 0);
		}, () -> {
			List<String> keys = ((ItemPickerScreen) mc.gui.screen()).keys(false);
			write("  " + keys.size() + ": " + keys);
			return expect(keys.size() >= 19 && keys.contains(DIAMOND) && keys.contains("block:minecraft:ancient_debris")
				&& keys.stream().allMatch(k -> k.endsWith("_ore") || k.endsWith("ancient_debris")), "wrong ore list");
		}, 5);
		add("screenshot of the ore category", () -> shot(mc, "pickertest-1-ores"), null, 10);

		add("shift + double click opens Advanced Setting and adds nothing yet", () -> doubleClick(mc, false, DIAMOND, SHIFT), () -> {
			if (!(mc.gui.screen() instanceof AdvancedPickScreen a)) {
				return "screen is " + mc.gui.screen();
			}
			String world = mc.level.getMinY() + " " + mc.level.getMaxY() + " ";
			return expect(picks.getList().isEmpty() && a.values().startsWith(world), "picks " + picks.getList() + ", values " + a.values());
		}, 5);
		add("screenshot of the default Advanced Setting", () -> shot(mc, "pickertest-2-default"), null, 10);
		add("Cancel adds nothing", () -> click(mc.gui.screen(), ((AdvancedPickScreen) mc.gui.screen()).buttonCenter(0), false, 0),
			() -> expect(mc.gui.screen() instanceof ItemPickerScreen && picks.getList().isEmpty(), "picks " + picks.getList()), 8);

		add("again, with Y range and colour", () -> doubleClick(mc, false, DIAMOND, SHIFT), null, 12);
		add("type the values", () -> ((AdvancedPickScreen) mc.gui.screen()).type(String.valueOf(low.getY()), String.valueOf(low.getY() + 1),
			"255", "0", "255"), null, 5);
		add("screenshot of the filled Advanced Setting", () -> shot(mc, "pickertest-3-filled"), null, 10);
		add("Add stores the pick and its options", () -> click(mc.gui.screen(), ((AdvancedPickScreen) mc.gui.screen()).buttonCenter(2), false, 0), () -> {
			PickAdvanced a = picks.advancedStore.advanced(DIAMOND);
			return expect(mc.gui.screen() instanceof ItemPickerScreen && picks.getList().equals(List.of(DIAMOND))
				&& a.equals(new PickAdvanced(low.getY(), low.getY() + 1, 0xFF00FF)), "picks " + picks.getList() + " " + a);
		}, 8);

		add("plain double click still adds at once, with defaults", () -> doubleClick(mc, false, GOLD, 0),
			() -> expect(mc.gui.screen() instanceof ItemPickerScreen && picks.getList().equals(List.of(DIAMOND, GOLD))
				&& picks.advancedStore.advanced(GOLD).isDefault(), "picks " + picks.getList()), 8);
		add("screenshot of the picked list", () -> shot(mc, "pickertest-4-picked"), null, 10);
		add("shift + double click on a picked card edits it", () -> doubleClick(mc, true, DIAMOND, SHIFT), () -> {
			if (!(mc.gui.screen() instanceof AdvancedPickScreen a)) {
				return "screen is " + mc.gui.screen();
			}
			return expect(a.values().equals(low.getY() + " " + (low.getY() + 1) + " 255 0 255"), "values " + a.values());
		}, 8);

		add("Block Finder marks only the ore inside the range, in the chosen colour", () -> {
			mc.gui.setScreen(null);
			setting("outline_transparency").setInt(0);
			setting("fill_transparency").setInt(72);
			if (!finder.isActive()) {
				finder.activate(mc);
			}
		}, () -> {
			List<BlockPos> marked = BlockFinderFeature.markedPositions();
			Integer line = BlockFinderFeature.outlineColorAt(low);
			return expect(marked.contains(low) && !marked.contains(high) && marked.contains(gold) && line != null && line == 0xFFFF00FF,
				"marked " + marked + " colour " + (line == null ? null : Integer.toHexString(line)));
		}, 40);
		add("screenshot of the overlay", () -> shot(mc, "pickertest-5-overlay"), null, 10);

		add("transparency: outline 50 %, fill 100 %", () -> {
			setting("outline_transparency").setInt(50);
			setting("fill_transparency").setInt(100);
		}, () -> {
			Integer line = BlockFinderFeature.outlineColorAt(low);
			return expect(line != null && line == 0x80FF00FF && BlockFinderFeature.faceCount() == 0,
				"colour " + (line == null ? null : Integer.toHexString(line)) + " faces " + BlockFinderFeature.faceCount());
		}, 30);
		add("screenshot without fill", () -> shot(mc, "pickertest-6-nofill"), null, 10);
		add("transparency: outline 0 %, fill 20 %", () -> {
			setting("outline_transparency").setInt(0);
			setting("fill_transparency").setInt(20);
		}, () -> expect(BlockFinderFeature.faceCount() > 0, "no faces"), 30);
		add("screenshot with strong fill", () -> shot(mc, "pickertest-7-fill"), null, 10);

		add("removing a pick forgets its options", () -> {
			picks.setList(List.of(GOLD));
		}, () -> expect(picks.advancedStore.advanced(DIAMOND).isDefault() && !BlockFinderFeature.markedPositions().contains(low),
			"still stored or still marked"), 30);

		add("Block Finder config screen has both sliders", () -> {
		}, () -> expect(setting("outline_transparency").type == Setting.Type.SLIDER && setting("fill_transparency").type == Setting.Type.SLIDER,
			"missing"), 1);

		add("restore", () -> {
			setting("outline_transparency").setInt(0);
			setting("fill_transparency").setInt(72);
			if (finder.isActive()) {
				finder.activate(mc);
			}
			picks.setList(oldPicks);
			setblock(mc, low, "air");
			setblock(mc, high, "air");
			setblock(mc, gold, "air");
		}, null, 20);
	}
}
