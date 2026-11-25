package khoa.tools.client.feature.freecam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import khoa.tools.client.config.KhoaCheat;
import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.client.feature.KhoaFeatures;
import khoa.tools.client.feature.camera.CameraWatch;
import khoa.tools.client.mixin.KeyMappingAccessor;

/**
 * In-process test of Freecam (dev only: {@code gradlew runClient -Pfreecamtest}). Flies the camera with a replaced key reader,
 * clicks through the real key mappings / Auto Clicker and checks the integrated server's answer.
 * Writes run/config/khoatools-freecamtest.log and run/screenshots/freecamtest-*.png, then closes the game.
 */
final class FreecamSelfTest {
	private record Step(String name, Runnable action, Supplier<String> check, int delay) {
	}

	private static final boolean AUTO = System.getProperty("khoatools.freecamtest") != null;
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
	private static Vec3 camBefore;
	private static Vec3 playerBefore;
	private static long nanosBefore;
	private static double flownSeconds;

	private FreecamSelfTest() {
	}

	static void tick(Minecraft mc) {
		if (!AUTO) {
			return;
		}
		if (!started) {
			mc.options.pauseOnLostFocus = false;
			if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen) {
				mc.gui.setScreen(null);
			}
			worldTicks = mc.player != null && mc.level != null && mc.gui.screen() == null ? worldTicks + 1 : 0;
			if (worldTicks > 100) {
				started = true;
				log = FabricLoader.getInstance().getConfigDir().resolve("khoatools-freecamtest.log");
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
		System.out.println("[KhoaTools freecamtest] " + line);
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

	private static String at(int dx, int dy, int dz) {
		BlockPos p = origin.offset(dx, dy, dz);
		return p.getX() + " " + p.getY() + " " + p.getZ();
	}

	private static KhoaFeature feature(String id) {
		return KhoaFeatures.all().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
	}

	/** Flies with these keys "held" until the next call. */
	private static void fly(KeyMapping... keys) {
		List<KeyMapping> held = List.of(keys);
		CameraWatch.keyReader = held::contains;
		camBefore = CameraWatch.pos();
		playerBefore = Minecraft.getInstance().player.position();
		nanosBefore = System.nanoTime();
	}

	private static Vec3 flown() {
		flownSeconds = (System.nanoTime() - nanosBefore) / 1.0e9;
		return CameraWatch.pos().subtract(camBefore);
	}

	private static void click(KeyMapping key) {
		KeyMappingAccessor acc = (KeyMappingAccessor) key;
		acc.khoa$setClickCount(acc.khoa$getClickCount() + 1);
	}

	private static float huskHealth(Minecraft mc) {
		for (var entity : mc.level.entitiesForRendering()) {
			if (entity instanceof net.minecraft.world.entity.monster.zombie.Husk husk && husk.isAlive()) {
				return ((LivingEntity) husk).getHealth();
			}
		}
		return -1;
	}

	private static void summonHusk(Minecraft mc) {
		cmd(mc, "kill @e[type=husk]");
		cmd(mc, "summon husk " + (origin.getX() + 0.5) + " " + origin.getY() + " " + (origin.getZ() + 2.5) + " {NoAI:1b,Silent:1b,PersistenceRequired:1b}");
	}

	private static void face(Minecraft mc) {
		cmd(mc, "tp @s " + (origin.getX() + 0.5) + " " + origin.getY() + " " + (origin.getZ() + 0.5) + " 0 0");
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, name + ".png", mc.gameRenderer.mainRenderTarget(), 1, c -> write("  screenshot " + name));
	}

	private static void build(Minecraft mc) {
		KhoaFeature freecam = feature("freecam");
		final CameraType[] cameraType = new CameraType[1];
		final float[] health = new float[1];

		add("setup: flat empty area, creative, face +Z", () -> {
			origin = mc.player.blockPosition();
			KhoaCheat.set(true);
			KhoaConfig.put("freecam", "entity_through_wall", false);
			KhoaConfig.put("freecam", "block_through_wall", false);
			CameraWatch.setSpeedH(6);
			CameraWatch.setSpeedV(12);
			mc.options.toggleCrouch().set(false);
			cmd(mc, "gamemode creative");
			cmd(mc, "time set noon");
			cmd(mc, "kill @e[type=!player]");
			cmd(mc, "fill " + at(-6, -1, -6) + " " + at(6, -1, 6) + " stone");
			cmd(mc, "fill " + at(-6, 0, -6) + " " + at(6, 6, 6) + " air");
			cmd(mc, "item replace entity @s weapon.mainhand with air");
			cmd(mc, "item replace entity @s weapon.offhand with air");
			face(mc);
		}, () -> expect(mc.player.onGround() || !mc.player.getAbilities().flying, "player is flying"), 40);

		add("stop flying and sneak", () -> {
			mc.player.getAbilities().flying = false;
			mc.options.keyShift.setDown(true);
		}, () -> expect(mc.player.isCrouching(), "not crouching"), 15);

		add("freecam on while sneaking, then let go of sneak: stays crouched", () -> {
			cameraType[0] = mc.options.getCameraType();
			freecam.activate(mc);
			fly();
			mc.options.keyShift.setDown(false);
		}, () -> expect(CameraWatch.isFree() && mc.player.isCrouching() && mc.player.input.keyPresses.shift()
			&& mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK, "crouching " + mc.player.isCrouching() + " free " + CameraWatch.isFree()), 20);

		add("mouse turns only the camera", () -> mc.player.turn(0, 40 / 0.15), () -> expect(
			Math.abs(CameraWatch.pitch() - 40) < 0.1 && Math.abs(mc.player.getXRot()) < 0.1, "camera " + CameraWatch.pitch() + " player " + mc.player.getXRot()), 3);

		add("W while looking down: flat movement at the horizontal speed", () -> {
			mc.options.keyUp.setDown(true);
			fly(mc.options.keyUp);
		}, () -> {
			Vec3 d = flown();
			double speed = d.z / flownSeconds;
			write("  moved " + d + " in " + flownSeconds + " s");
			mc.options.keyUp.setDown(false);
			return expect(Math.abs(d.y) < 1.0e-6 && Math.abs(d.x) < 1.0e-6 && speed > 4.8 && speed < 7.2
				&& mc.player.position().distanceTo(playerBefore) < 0.01, "delta " + d + " speed " + speed + " player moved " + mc.player.position().distanceTo(playerBefore));
		}, 20);

		add("released: stops at once (no acceleration)", () -> fly(), () -> {
			Vec3 d = flown();
			return expect(d.length() < 1.0e-9, "drifted " + d);
		}, 8);

		add("jump: straight up at the vertical speed", () -> fly(mc.options.keyJump), () -> {
			Vec3 d = flown();
			double speed = d.y / flownSeconds;
			write("  moved " + d + " speed " + speed);
			return expect(Math.abs(d.x) < 1.0e-6 && Math.abs(d.z) < 1.0e-6 && speed > 9.6 && speed < 14.4 && mc.player.position().distanceTo(playerBefore) < 0.01, "speed " + speed);
		}, 10);

		add("shift in freecam: straight down, player unchanged", () -> fly(mc.options.keyShift), () -> {
			Vec3 d = flown();
			return expect(d.y < -3 && Math.abs(d.x) < 1.0e-6 && Math.abs(d.z) < 1.0e-6 && mc.player.isCrouching(), "delta " + d);
		}, 10);

		add("freecam off: first person again, player stands up", () -> {
			fly();
			freecam.activate(mc);
		}, () -> expect(!CameraWatch.isWatching() && mc.options.getCameraType() == cameraType[0] && !mc.player.isCrouching(),
			"camera " + mc.options.getCameraType() + " crouching " + mc.player.isCrouching()), 15);

		add("standing: shift pressed in freecam never crouches the player", () -> {
			freecam.activate(mc);
			mc.options.keyShift.setDown(true);
			fly(mc.options.keyShift);
		}, () -> expect(!mc.player.isCrouching() && !mc.player.input.keyPresses.shift(), "player crouched"), 10);

		add("toggle sneak option: a press in freecam is undone on exit", () -> {
			mc.options.keyShift.setDown(false);
			freecam.activate(mc);
			mc.options.toggleCrouch().set(true);
			freecam.activate(mc);
			fly();
		}, () -> expect(CameraWatch.isFree() && !mc.options.keyShift.isDown(), "state"), 8);

		add("toggle sneak option: exit", () -> {
			mc.options.keyShift.setDown(true);
			freecam.activate(mc);
		}, () -> {
			boolean down = mc.options.keyShift.isDown();
			mc.options.toggleCrouch().set(false);
			mc.options.keyShift.setDown(false);
			return expect(!down, "sneak stayed toggled on");
		}, 8);

		add("husk in front, freecam on and fly away", () -> {
			face(mc);
			summonHusk(mc);
		}, () -> expect(huskHealth(mc) == 20, "husk health " + huskHealth(mc)), 30);

		add("fly the camera back and up", () -> {
			freecam.activate(mc);
			fly(mc.options.keyDown, mc.options.keyLeft);
		}, () -> expect(CameraWatch.isFree() && flown().horizontalDistance() > 2, "did not fly"), 14);

		add("look back at the player (own model and name tag)", () -> {
			fly();
			Vec3 target = mc.player.position().add(0, 1.4, 0);
			Vec3 d = target.subtract(CameraWatch.pos());
			double wantYaw = Math.toDegrees(Math.atan2(-d.x, d.z));
			double wantPitch = -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
			mc.player.turn((wantYaw - CameraWatch.yaw()) / 0.15, (wantPitch - CameraWatch.pitch()) / 0.15);
		}, null, 10);

		add("screenshot of yourself", () -> shot(mc, "freecamtest-1-self"), null, 10);

		add("turn the camera away; Auto Clicker still hits what the head aims at", () -> {
			mc.player.turn(180 / 0.15, 0);
			fly(mc.options.keyDown);
			KhoaConfig.put("autoclicker", "mode", 0);
			KhoaConfig.put("autoclicker", "interval_ms", 600);
			KhoaConfig.put("autoclicker", "hold_ms", 0);
			KhoaConfig.put("autoclicker", "random_ms", 0);
			KhoaConfig.put("autoclicker", "cam_lock", false);
			feature("autoclicker").activate(mc);
		}, () -> {
			fly();
			feature("autoclicker").activate(mc);
			return expect(huskHealth(mc) >= 0 && huskHealth(mc) < 20, "husk health " + huskHealth(mc));
		}, 40);

		add("wall in front of a fresh husk", () -> {
			summonHusk(mc);
			cmd(mc, "fill " + at(-1, 0, 1) + " " + at(1, 2, 1) + " stone");
		}, () -> expect(huskHealth(mc) == 20 && mc.hitResult instanceof BlockHitResult, "health " + huskHealth(mc) + " hit " + mc.hitResult), 30);

		add("entity option on: the aim reaches the husk through the wall", () -> KhoaConfig.put("freecam", "entity_through_wall", true),
			() -> expect(mc.hitResult instanceof EntityHitResult, "hit " + mc.hitResult), 5);

		add("attack through the wall", () -> click(mc.options.keyAttack),
			() -> expect(huskHealth(mc) < 20 && mc.level.getBlockState(origin.offset(0, 1, 1)).isSolidRender(), "husk health " + huskHealth(mc)), 20);

		add("screenshot with the through wall line", () -> shot(mc, "freecamtest-2-through"), null, 10);

		add("cheat false: options hidden and without effect", () -> KhoaCheat.set(false),
			() -> expect(freecam.settings().size() == 2 && mc.hitResult instanceof BlockHitResult && CameraWatch.isFree(),
				"rows " + freecam.settings().size() + " hit " + mc.hitResult + " free " + CameraWatch.isFree()), 5);

		add("cheat true: four rows again", () -> {
			KhoaCheat.set(true);
			KhoaConfig.put("freecam", "entity_through_wall", false);
		}, () -> expect(freecam.settings().size() == 4 && mc.hitResult instanceof BlockHitResult, "rows " + freecam.settings().size()), 5);

		add("freecam off with option on: no through wall", () -> {
			KhoaConfig.put("freecam", "entity_through_wall", true);
			freecam.activate(mc);
		}, () -> expect(!CameraWatch.isFree() && mc.hitResult instanceof BlockHitResult, "hit " + mc.hitResult), 8);

		add("chest behind the wall", () -> {
			KhoaConfig.put("freecam", "entity_through_wall", false);
			cmd(mc, "kill @e[type=husk]");
			cmd(mc, "setblock " + at(0, 1, 2) + " chest[facing=north]");
			face(mc);
			freecam.activate(mc);
		}, () -> expect(mc.hitResult instanceof BlockHitResult h && h.getBlockPos().equals(origin.offset(0, 1, 1)), "hit " + mc.hitResult), 20);

		add("block option off: right click does not open it", () -> click(mc.options.keyUse),
			() -> expect(mc.gui.screen() == null, "screen " + mc.gui.screen()), 15);

		add("block option on: right click opens the chest through the wall", () -> {
			KhoaConfig.put("freecam", "block_through_wall", true);
			click(mc.options.keyUse);
		}, () -> expect(mc.gui.screen() instanceof AbstractContainerScreen<?>, "screen " + mc.gui.screen()), 20);

		add("close the chest; the freecam is still on", () -> mc.player.closeContainer(),
			() -> expect(CameraWatch.isFree() && mc.gui.screen() == null, "screen " + mc.gui.screen()), 10);

		add("tidy up", () -> {
			freecam.activate(mc);
			KhoaConfig.put("freecam", "block_through_wall", false);
			CameraWatch.setSpeedH(10);
			CameraWatch.setSpeedV(10);
			cmd(mc, "fill " + at(-1, 0, 1) + " " + at(1, 2, 2) + " air");
		}, () -> expect(!CameraWatch.isWatching(), "still watching"), 10);
	}
}
