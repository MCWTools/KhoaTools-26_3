package khoa.tools.client.feature.heightlaser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.platform.InputConstants;

import khoa.api.render.KhoaBuildPreview;
import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.client.feature.KhoaFeatures;
import khoa.tools.setting.Setting;

/**
 * In-process test of Draw Shape V2 and Height Laser (dev only, enabled by {@code -Dkhoatools.autotest=true}, see
 * {@code gradlew runClient -Pautotest}). It drives the real code paths from the client tick, writes
 * run/config/khoatools-selftest.log, run/screenshots/selftest-*.png and closes the game when done.
 */
final class FeatureSelfTest {
	private record Step(String name, Runnable action, Supplier<String> check, int delay) {
	}

	private static final boolean AUTO = System.getProperty("khoatools.autotest") != null && System.getProperty("khoatools.pallettest") == null;
	private static final List<Step> STEPS = new ArrayList<>();
	private static boolean built;
	private static boolean started;
	private static int index;
	private static int wait;
	private static boolean acted;
	private static int worldTicks;
	private static int passes;
	private static int failures;
	private static Path log;
	private static Vec3 base = Vec3.ZERO;

	private FeatureSelfTest() {
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
				log = FabricLoader.getInstance().getConfigDir().resolve("khoatools-selftest.log");
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
		String result = s.check() == null ? null : s.check().get();
		if (result == null) {
			passes++;
			write("  ok");
		} else {
			failures++;
			write("  FAIL " + result);
		}
	}

	private static void write(String line) {
		System.out.println("[KhoaTools selftest] " + line);
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

	private static Setting setting(KhoaFeature f, String key) {
		for (Setting s : f.settings()) {
			if (s.key.equals(key)) {
				return s;
			}
		}
		throw new IllegalStateException("no setting " + key);
	}

	private static KhoaFeature feature(String id) {
		return KhoaFeatures.all().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
	}

	private static void build(Minecraft mc) {
		if (built) {
			return;
		}
		built = true;
		// ------------------------------------------------ world
		add("build platform and wall", () -> {
			mc.gui.setScreen(null);
			for (String c : new String[] { "gamemode creative", "time set day", "gamerule advance_time false" }) {
				cmd(mc, c);
			}
			base = mc.player.position();
			cmd(mc, "fill ~-14 ~-1 ~-14 ~14 ~-1 ~14 stone");
			cmd(mc, "fill ~-14 ~ ~-14 ~14 ~12 ~14 air");
			cmd(mc, "fill ~-4 ~ ~7 ~4 ~5 ~7 bricks");
			mc.player.setYRot(0);
			mc.player.setXRot(0);
		}, () -> null, 60);

		// ------------------------------------------------ Draw Shape V2
		KhoaFeature ds = feature("drawshape2");
		add("DrawShapeV2: square radius 4 facing +z", () -> {
			KhoaConfig.put("drawshape2", "shape", 2);
			KhoaConfig.put("drawshape2", "angle", 0);
			KhoaConfig.put("drawshape2", "distance", 4);
			KhoaConfig.put("drawshape2", "thickness", 0);
			KhoaConfig.put("drawshape2", "center", 0);
			mc.player.setYRot(0);
			mc.player.setXRot(0);
			setting(ds, "draw").run();
		}, () -> {
			KhoaBuildPreview.Preview p = KhoaBuildPreview.get("khoatools.drawshape2");
			if (khoa.tools.client.feature.drawshape2.DrawShapeV2Feature.usesLitematica()) {
				write("  (Litematica placement in use)");
				return expect(ds.isActive(), "feature not active");
			}
			if (p == null || !ds.isActive()) {
				return "no preview or feature not active";
			}
			return expect(p.size() == 32 && p.isVisible(), "size " + p.size() + " visible " + p.isVisible());
		}, 25);
		add("DrawShapeV2: line 12 blocks horizontal into the wall -> red cells", () -> {
			KhoaConfig.put("drawshape2", "shape", 0);
			KhoaConfig.put("drawshape2", "angle", 1);
			KhoaConfig.put("drawshape2", "distance", 12);
			mc.player.setYRot(0);
			mc.player.setXRot(35);
			setting(ds, "draw").run();
		}, () -> {
			KhoaBuildPreview.Preview p = KhoaBuildPreview.get("khoatools.drawshape2");
			if (khoa.tools.client.feature.drawshape2.DrawShapeV2Feature.usesLitematica()) {
				return null;
			}
			return p == null ? "no preview" : expect(p.size() == 13 && p.obstructedCount() == 1, "size " + p.size() + " obstructed " + p.obstructedCount());
		}, 25);
		add("DrawShapeV2: screenshot of the ghost line", () -> {
			mc.player.setXRot(20);
			shot(mc, "selftest-drawshape");
		}, () -> null, 30);
		add("DrawShapeV2: card off hides the preview", () -> ds.activate(mc), () -> {
			KhoaBuildPreview.Preview p = KhoaBuildPreview.get("khoatools.drawshape2");
			if (khoa.tools.client.feature.drawshape2.DrawShapeV2Feature.usesLitematica()) {
				return "Litematica placement still there";
			}
			return expect(!ds.isActive() && (p == null || !p.isVisible()), "still visible");
		}, 5);
		add("DrawShapeV2: card on shows it again", () -> ds.activate(mc), () -> {
			KhoaBuildPreview.Preview p = KhoaBuildPreview.get("khoatools.drawshape2");
			if (khoa.tools.client.feature.drawshape2.DrawShapeV2Feature.usesLitematica()) {
				return null;
			}
			return expect(ds.isActive() && p != null && p.isVisible(), "not visible");
		}, 5);
		add("DrawShapeV2: off before laser test", () -> ds.activate(mc), () -> null, 5);

		// ------------------------------------------------ Height Laser
		KhoaFeature hl = feature("heightlaser");
		add("Laser: place on the wall face", () -> {
			LaserStore.sync(mc);
			removeAllLasers();
			mc.player.setYRot(0);
			mc.player.setXRot(-5);
			lookAt(mc, base.add(0, 2.5, 7));
			HeightLaserFeature.place(mc);
		}, () -> expect(LaserStore.all().size() == 1 && hl.isActive(), "lasers " + LaserStore.all().size()), 5);
		add("Laser: configure rotation, ports, on", () -> {
			Laser l = LaserStore.all().get(0);
			l.on = true;
			l.yaw = 35;
			l.pitch = 20;
			l.ports[1] = true;
			l.length = 40;
			l.changed();
		}, () -> null, 10);
		add("Laser: aim ray hits the device", () -> {
			Laser l = LaserStore.all().get(0);
			lookAt(mc, l.center());
		}, () -> expect(HeightLaserFeature.aim(mc) != null, "aim returned null"), 5);
		add("Laser: right click is consumed (FAIL, no packet) and opens the screen", () -> {
			InteractionResult r = HeightLaserFeature.use(true, InteractionHand.MAIN_HAND);
			if (r != InteractionResult.FAIL) {
				write("  use returned " + r);
			}
		}, () -> expect(mc.gui.screen() instanceof LaserScreen, "screen is " + mc.gui.screen()), 15);
		add("Laser: viewport renders (dump png)", () -> {
		}, () -> {
			try {
				((LaserScreen) mc.gui.screen()).dumpViewport(mc.gameDirectory.toPath().resolve("screenshots").resolve("selftest-viewport.png"));
				Files.createDirectories(mc.gameDirectory.toPath().resolve("screenshots"));
			} catch (IOException e) {
				return e.toString();
			}
			return null;
		}, 20);
		add("Laser: dump viewport again after dirs created", () -> {
			try {
				Files.createDirectories(mc.gameDirectory.toPath().resolve("screenshots"));
				((LaserScreen) mc.gui.screen()).dumpViewport(mc.gameDirectory.toPath().resolve("screenshots").resolve("selftest-viewport.png"));
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			shot(mc, "selftest-laser-screen");
		}, () -> null, 20);
		add("Laser screen: click slider changes yaw", () -> {
			LaserScreen s = (LaserScreen) mc.gui.screen();
			for (GuiEventListener w : s.widgets()) {
				if (w instanceof AbstractSliderButton b && b.getMessage().getString().startsWith("Yaw")) {
					double x = b.getX() + b.getWidth() * 0.25;
					double y = b.getY() + 10;
					s.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
					s.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
					return;
				}
			}
			throw new IllegalStateException("Yaw slider not found");
		}, () -> expect(Math.abs(LaserStore.all().get(0).yaw - (-90)) <= 8, "yaw " + LaserStore.all().get(0).yaw), 5);
		add("Laser screen: click On/Off button", () -> {
			LaserScreen s = (LaserScreen) mc.gui.screen();
			for (GuiEventListener w : s.widgets()) {
				if (w instanceof Button b && b.getMessage().getString().startsWith("Laser:")) {
					double x = b.getX() + 5;
					double y = b.getY() + 10;
					s.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
					s.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
					return;
				}
			}
			throw new IllegalStateException("On/Off button not found");
		}, () -> expect(!LaserStore.all().get(0).on, "still on"), 5);
		add("Laser screen: turn on again, drag in viewport turns auto view off", () -> {
			LaserStore.all().get(0).on = true;
			LaserStore.all().get(0).changed();
			LaserScreen s = (LaserScreen) mc.gui.screen();
			double[] vc = s.viewportCenter();
			MouseButtonEvent e = new MouseButtonEvent(vc[0], vc[1], new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
			s.mouseClicked(e, false);
			s.mouseDragged(e, 20, 5);
			s.mouseReleased(e);
		}, () -> expect(!((LaserScreen) mc.gui.screen()).autoView(), "auto view still on"), 20);
		add("Laser screen: close", () -> mc.gui.setScreen(null), () -> expect(mc.gui.screen() == null, "screen open"), 5);
		add("Laser: shift + right click toggles the port under the crosshair", () -> {
			Laser l = LaserStore.all().get(0);
			l.yaw = 0;
			l.pitch = 0;
			l.changed();
			lookAt(mc, l.center());
			sneak(mc, true);
			boolean before = l.ports[HeightLaserFeature.aim(mc).hit().port()];
			HeightLaserFeature.use(true, InteractionHand.MAIN_HAND);
			boolean after = l.ports[HeightLaserFeature.aim(mc).hit().port()];
			sneak(mc, false);
			if (before == after) {
				throw new IllegalStateException("port did not toggle");
			}
		}, () -> null, 5);
		add("Laser: world screenshot with the beams", () -> {
			Laser l = LaserStore.all().get(0);
			l.on = true;
			for (int i = 0; i < 6; i++) {
				l.ports[i] = i == 1 || i == 5 || i == 2;
			}
			l.length = 30;
			l.changed();
			mc.player.setYRot(30);
			mc.player.setXRot(10);
			cmd(mc, "tp @s " + base.x + " " + base.y + " " + base.z + " 15 8");
		}, () -> null, 40);
		add("Laser: screenshot", () -> shot(mc, "selftest-laser-world"), () -> null, 30);
		add("Laser: shift + left click removes the device", () -> {
			Laser l = LaserStore.all().get(0);
			lookAt(mc, l.center());
			sneak(mc, true);
			boolean cancelled = ClientPreAttackCallback.EVENT.invoker().onClientPlayerPreAttack(mc, mc.player, 0);
			sneak(mc, false);
			if (!cancelled) {
				throw new IllegalStateException("attack was not cancelled");
			}
		}, () -> expect(LaserStore.all().isEmpty(), "lasers left: " + LaserStore.all().size()), 5);
	}

	/** Fakes the sneak key for the local player (LocalPlayer reads it from its input, not from the shared flag). */
	private static void sneak(Minecraft mc, boolean down) {
		mc.player.input.keyPresses = new net.minecraft.world.entity.player.Input(false, false, false, false, false, down, false);
	}

	private static void removeAllLasers() {
		for (Laser l : List.copyOf(LaserStore.all())) {
			LaserStore.remove(l);
		}
	}
}
