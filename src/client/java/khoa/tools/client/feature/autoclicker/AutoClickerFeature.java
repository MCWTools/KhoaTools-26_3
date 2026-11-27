package khoa.tools.client.feature.autoclicker;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.camlock.CamLockFeature;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.client.mixin.KeyMappingAccessor;
import khoa.tools.setting.Setting;

/**
 * Clicks Attack or Use on a timer. With the Cam Lock option it also locks the camera where you look when it starts. Stays on after you leave/rejoin a server; pauses while a screen is open.
 * Timing uses System.nanoTime() in milliseconds, not game ticks. Clicks and key presses are still handed to the game once per client tick,
 * so an interval below 50 ms sends several clicks in the same tick to keep the rate.
 * Hold time 0 = single click. With a hold time, the key is held first and the interval only starts counting after the release.
 */
public final class AutoClickerFeature implements KhoaFeature {
	private enum Phase {
		WAIT, HOLD
	}

	private static boolean on;
	private static Phase phase = Phase.WAIT;
	/** nanoTime of the next click (WAIT) or of the release (HOLD). */
	private static long deadline;
	private static boolean paused = true;
	/** Set by Auto Eat: the clicker stands still (and lets go of the key) until it is cleared. */
	private static boolean suspended;
	/** True when this clicker (not the player) turned Cam Lock on, so it also turns it off. */
	private static boolean ownsCamLock;
	private static final int MAX_CLICKS_PER_TICK = 20;

	@Override
	public String id() {
		return "autoclicker";
	}

	@Override
	public String displayName() {
		return "Auto Clicker";
	}

	@Override
	public String description() {
		return "Automatically clicks Attack or Use (farming, mob grinders). Click the card to turn it on/off; it stays on when you rejoin. "
			+ "Pauses while a menu is open. Intervals are in milliseconds (real time, not ticks). Hold time 0 = one click per interval.";
	}

	@Override
	public List<Setting> settings() {
		String id = id();
		return List.of(
			Setting.choice("mode", "Mode", "Attack = left click, Use = right click.", List.of("Attack", "Use"),
				() -> KhoaConfig.getInt(id, "mode", 0), v -> {
					release(Minecraft.getInstance());
					KhoaConfig.put(id, "mode", v);
				}),
			Setting.integer("interval_ms", "Interval (ms)", "Time between clicks in milliseconds (real time). Below 50 ms several clicks are sent in the same game tick.",
				1, 3_600_000, () -> KhoaConfig.getInt(id, "interval_ms", 500), v -> KhoaConfig.put(id, "interval_ms", v)),
			Setting.integer("random_ms", "Randomness (ms)", "Random extra delay added to every interval (never subtracted). Interval 700 + randomness 500 = each wait is random between 700 and 1200 ms. 0 = fixed interval.",
				0, 3_600_000, () -> KhoaConfig.getInt(id, "random_ms", 0), v -> KhoaConfig.put(id, "random_ms", v)),
			Setting.integer("hold_ms", "Hold time (ms)", "How long the button is held per click, in milliseconds. 0 = a single click. The interval starts after the hold ends.",
				0, 3_600_000, () -> KhoaConfig.getInt(id, "hold_ms", 0), v -> KhoaConfig.put(id, "hold_ms", v)),
			Setting.integer("lock_slot", "Lock hotbar slot", "Keeps this hotbar slot selected while the clicker is on. 0 = disabled.",
				0, 9, () -> KhoaConfig.getInt(id, "lock_slot", 0), v -> KhoaConfig.put(id, "lock_slot", v)),
			Setting.bool("cam_lock", "Cam Lock", "When the clicker is turned on, locks the camera at the angle you look at now (Cam Lock feature) and unlocks it when the clicker is turned off. "
				+ "Look at the farm, turn the clicker on. Works with Camera Watch: the player keeps aiming at the farm while the camera is elsewhere.",
				() -> KhoaConfig.getBool(id, "cam_lock", false), v -> KhoaConfig.put(id, "cam_lock", v)));
	}

	@Override
	public boolean isActive() {
		return on;
	}

	@Override
	public void activate(Minecraft mc) {
		on = !on;
		release(mc);
		paused = true; // first click on the next tick
		if (on && KhoaConfig.getBool("autoclicker", "cam_lock", false) && !CamLockFeature.isLocked() && CamLockFeature.lockHere(mc)) {
			ownsCamLock = true;
		} else if (!on && ownsCamLock) {
			CamLockFeature.unlock();
			ownsCamLock = false;
		}
		KhoaFeature.say(mc, "Auto Clicker: " + (on ? "on" : "off"));
	}

	@Override
	public void onInit() {
		ClientTickEvents.START_CLIENT_TICK.register(AutoClickerFeature::tick);
	}

	public static void suspend(boolean value) {
		suspended = value;
	}

	private static KeyMapping key(Minecraft mc) {
		return KhoaConfig.getInt("autoclicker", "mode", 0) == 0 ? mc.options.keyAttack : mc.options.keyUse;
	}

	private static void release(Minecraft mc) {
		if (phase == Phase.HOLD) {
			mc.options.keyAttack.setDown(false);
			mc.options.keyUse.setDown(false);
		}
		phase = Phase.WAIT;
	}

	/** Base interval plus a random 0..randomness extra (only adds, never subtracts), in nanoseconds. */
	private static long nextInterval() {
		long base = Math.max(1, KhoaConfig.getInt("autoclicker", "interval_ms", 500));
		long random = Math.max(0, KhoaConfig.getInt("autoclicker", "random_ms", 0));
		long extra = random > 0 ? ThreadLocalRandom.current().nextLong(random + 1) : 0;
		return (base + extra) * 1_000_000L;
	}

	private static void click(KeyMapping key) {
		KeyMappingAccessor acc = (KeyMappingAccessor) key;
		acc.khoa$setClickCount(acc.khoa$getClickCount() + 1);
	}

	private static void tick(Minecraft mc) {
		if (!on) {
			return;
		}
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gui.screen() != null || suspended) {
			release(mc);
			paused = true;
			return;
		}

		int slot = KhoaConfig.getInt("autoclicker", "lock_slot", 0);
		if (slot >= 1 && slot <= 9 && player.getInventory().getSelectedSlot() != slot - 1) {
			player.getInventory().setSelectedSlot(slot - 1);
		}

		long now = System.nanoTime();
		if (paused) {
			paused = false;
			deadline = now; // first click right away after turning on / closing a menu
		}
		KeyMapping key = key(mc);
		long hold = Math.max(0, KhoaConfig.getInt("autoclicker", "hold_ms", 0)) * 1_000_000L;
		if (phase == Phase.HOLD) {
			key.setDown(true);
			if (now - deadline >= 0) {
				key.setDown(false);
				phase = Phase.WAIT;
				deadline = now + nextInterval(); // the interval starts only now, after the hold
			}
			return;
		}
		if (now - deadline < 0) {
			return;
		}
		if (hold > 0) {
			click(key);
			key.setDown(true);
			phase = Phase.HOLD;
			deadline = now + hold;
			return;
		}
		int clicks = 0;
		while (now - deadline >= 0 && clicks < MAX_CLICKS_PER_TICK) { // catch up: short intervals send several clicks per tick
			click(key);
			clicks++;
			deadline += nextInterval();
		}
		if (now - deadline >= 0) {
			deadline = now + nextInterval(); // far behind (lag spike): do not burst afterwards
		}
	}
}
