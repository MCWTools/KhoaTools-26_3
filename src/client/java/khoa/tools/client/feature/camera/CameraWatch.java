package khoa.tools.client.feature.camera;

import java.util.function.Predicate;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.mixin.KeyMappingKeyAccessor;

/**
 * Runtime state of the detached view: "watching" a saved camera, or Freecam. In both the player stays frozen.
 * Freecam only moves the render camera: the player keeps its own aim, so attacking / using (also by the Auto Clicker) still
 * happens where the player's head looks.
 */
public final class CameraWatch {
	private static boolean watching;
	private static boolean fromKey;
	private static String name = "";
	private static String worldKey;
	private static Vec3 pos = Vec3.ZERO;
	private static float yaw;
	private static float pitch;
	private static boolean free;
	/** The defaults are the values saved by the older freecam inside the Camera feature. */
	private static int speedH = KhoaConfig.getInt("freecam", "speed_h", KhoaConfig.getInt("camera", "freecamSpeedH", 10));
	private static int speedV = KhoaConfig.getInt("freecam", "speed_v", KhoaConfig.getInt("camera", "freecamSpeedV", 10));
	private static long lastNanos;
	/** How freecam reads a movement key. Replaced only by the in-process self test. */
	public static Predicate<KeyMapping> keyReader = CameraWatch::physicallyDown;
	/** The player that started freecam (a respawn / dimension change creates a new one). */
	private static LocalPlayer freePlayer;
	private static CameraType previousCameraType;
	private static boolean keepShift;
	private static boolean toggleStateSaved;
	private static boolean sneakToggled;
	private static boolean sprintToggled;

	private CameraWatch() {
	}

	public static boolean isWatching() {
		return watching;
	}

	public static boolean isFromKey() {
		return watching && fromKey;
	}

	public static String name() {
		return name;
	}

	public static Vec3 pos() {
		return pos;
	}

	public static float yaw() {
		return yaw;
	}

	public static float pitch() {
		return pitch;
	}

	public static void start(Minecraft mc, CameraStore.Cam cam, boolean viaKey) {
		stop();
		watching = true;
		fromKey = viaKey;
		name = cam.name;
		worldKey = CameraStore.worldKey(mc);
		pos = new Vec3(cam.x, cam.y, cam.z);
		yaw = cam.yaw;
		pitch = cam.pitch;
	}

	/**
	 * Freecam: the view starts at the current camera and flies with the movement keys; the player stays frozen. A player that
	 * is sneaking stays sneaking (except while flying, where holding sneak would make it sink).
	 */
	public static void startFree(Minecraft mc) {
		stop();
		LocalPlayer player = mc.player;
		if (player == null) return;
		net.minecraft.client.Camera cam = mc.gameRenderer.mainCamera();
		pos = cam.position();
		yaw = cam.yRot();
		pitch = cam.xRot();
		keepShift = player.input.keyPresses.shift() && !player.getAbilities().flying;
		freePlayer = player;
		toggleStateSaved = false;
		// third person: the own model is drawn, the first person hand / crosshair / screen overlays are not
		previousCameraType = mc.options.getCameraType();
		mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		watching = true;
		fromKey = false;
		free = true;
		name = "Freecam";
		worldKey = CameraStore.worldKey(mc);
		lastNanos = System.nanoTime();
	}

	public static boolean isFree() {
		return watching && free;
	}

	/** True when the frozen player has to keep sneaking (never while only watching a saved camera). */
	public static boolean keepShift() {
		return isFree() && keepShift;
	}

	public static int speedH() {
		return speedH;
	}

	public static int speedV() {
		return speedV;
	}

	/** Slider / config setters (blocks per second, 1..100). */
	public static void setSpeedH(int value) {
		speedH = Math.max(1, Math.min(100, value));
		KhoaConfig.put("freecam", "speed_h", speedH);
	}

	public static void setSpeedV(int value) {
		speedV = Math.max(1, Math.min(100, value));
		KhoaConfig.put("freecam", "speed_v", speedV);
	}

	/** Puts the freecam at an exact spot (used by the in-process self test). */
	public static void moveTo(Vec3 position, float newYaw, float newPitch) {
		pos = position;
		yaw = newYaw;
		pitch = newPitch;
	}

	/** Mouse wheel while in freecam: both speeds faster / slower by at least 1 (saved, so the sliders follow). */
	public static void scroll(double amount) {
		if (amount == 0) return;
		setSpeedH(scaled(speedH, amount));
		setSpeedV(scaled(speedV, amount));
	}

	private static int scaled(int speed, double amount) {
		int next = (int) Math.round(Math.max(1.0, Math.min(100.0, speed * Math.pow(1.15, amount))));
		return next != speed ? next : speed + (amount > 0 ? 1 : -1);
	}

	/**
	 * The key as it is held on the keyboard, so "toggle sneak" or the Sneak feature (which keep the sneak mapping down)
	 * do not make the camera sink forever. Mouse bound keys use the mapping state.
	 */
	private static boolean physicallyDown(KeyMapping mapping) {
		InputConstants.Key key = ((KeyMappingKeyAccessor) mapping).khoa$getKey();
		if (key.getType() == InputConstants.Type.KEYBOARD && key.getValue() != InputConstants.UNKNOWN.getValue()) {
			return InputConstants.isKeyDown(key.getValue());
		}
		return mapping.isDown();
	}

	/**
	 * Once per rendered frame: moves the freecam without any acceleration. Forward / back / strafe go along the camera's yaw on
	 * the horizontal plane (looking up or down never tilts the movement), jump / sneak go straight up / down.
	 */
	public static void frame(Minecraft mc) {
		long now = System.nanoTime();
		double dt = Math.min(0.1, (now - lastNanos) / 1.0e9);
		lastNanos = now;
		if (!isFree() || mc.player == null || mc.level == null) return;
		// F5 must not bring the first person hand back
		if (mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		if (mc.gui.screen() != null) return;
		if (!toggleStateSaved) {
			toggleStateSaved = true;
			sneakToggled = mc.options.keyShift.isDown();
			sprintToggled = mc.options.keySprint.isDown();
		}
		double forward = held(mc.options.keyUp) - held(mc.options.keyDown);
		double side = held(mc.options.keyLeft) - held(mc.options.keyRight);
		double up = held(mc.options.keyJump) - held(mc.options.keyShift);
		double length = Math.hypot(forward, side);
		if (length > 1) {
			forward /= length;
			side /= length;
		}
		double yawRad = Math.toRadians(yaw);
		double fx = -Math.sin(yawRad);
		double fz = Math.cos(yawRad);
		double dx = fx * forward + fz * side;
		double dz = fz * forward - fx * side;
		double stepH = speedH * dt;
		// the client only has the chunks around the player, so there is nothing to see further away
		double max = Math.max(1, mc.options.getEffectiveRenderDistance() - 1) * 16.0;
		double nx = Math.max(mc.player.getX() - max, Math.min(mc.player.getX() + max, pos.x + dx * stepH));
		double nz = Math.max(mc.player.getZ() - max, Math.min(mc.player.getZ() + max, pos.z + dz * stepH));
		pos = new Vec3(nx, pos.y + up * speedV * dt, nz);
	}

	private static int held(KeyMapping mapping) {
		return keyReader.test(mapping) ? 1 : 0;
	}

	public static void stop() {
		if (free) {
			Minecraft mc = Minecraft.getInstance();
			if (previousCameraType != null) mc.options.setCameraType(previousCameraType);
			if (toggleStateSaved) {
				// undo sneak / sprint presses that only flew the camera ("toggle" options flip on every press)
				restoreToggle(mc.options.keyShift, mc.options.toggleCrouch().get(), sneakToggled);
				restoreToggle(mc.options.keySprint, mc.options.toggleSprint().get(), sprintToggled);
			}
			freePlayer = null;
		}
		watching = false;
		fromKey = false;
		free = false;
	}

	private static void restoreToggle(KeyMapping key, boolean toggleMode, boolean wanted) {
		if (toggleMode && key.isDown() != wanted) key.setDown(true);
	}

	/** Called every tick: ends the watch when the world / dimension changed. */
	public static void validate(Minecraft mc) {
		if (watching && (mc.level == null || !java.util.Objects.equals(worldKey, CameraStore.worldKey(mc))
			|| (free && (mc.player != freePlayer || !mc.player.isAlive())))) {
			stop();
		}
	}

	/** Mouse movement while watching (same scale as {@code Entity.turn}). Ignored unless rotateable is on. */
	public static void turn(double dYaw, double dPitch) {
		if (!free && !CameraFeature.rotateable()) return;
		yaw += (float) dYaw * 0.15F;
		pitch = Math.max(-90.0F, Math.min(90.0F, pitch + (float) dPitch * 0.15F));
	}
}
