package khoa.tools.client.feature.automation;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.mixin.FishingHookAccessor;
import khoa.tools.setting.Setting;

/**
 * Reels in when a fish bites, with human-like timing: a random delay and a chance to make a mistake (forget to reel, reel late, reel too early).
 * All waiting uses System.nanoTime().
 */
public final class AutoFishFeature extends ToggleFeature {
	private static final String ID = "autofish";

	private enum Plan {
		NORMAL, MISS, LATE, EARLY
	}

	private int hookId = -1;
	private Plan plan = Plan.NORMAL;
	private long reelAt;
	private boolean reelScheduled;
	private long castAt;
	private boolean castScheduled;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Auto Fish";
	}

	@Override
	public String description() {
		return "Hold a fishing rod and cast once. When a fish bites it reels in after a random delay (0 to the slider value) and casts again. "
			+ "Success chance below 100% makes it fail sometimes the way a person does: forgetting to reel, reeling late, or reeling too early. Click the card to turn it on/off.";
	}

	@Override
	public List<Setting> settings() {
		return List.of(
			Setting.slider("delay_ms", "Random catch delay (ms)", "After a bite it waits a random time between 0 and this many milliseconds before reeling.", 0, 1000,
				() -> KhoaConfig.getInt(ID, "delay_ms", 300), v -> KhoaConfig.put(ID, "delay_ms", v)),
			Setting.slider("success", "Success chance (%)", "Chance that a bite is reeled in correctly. The rest are mistakes: forget, late or early.", 0, 100,
				() -> KhoaConfig.getInt(ID, "success", 95), v -> KhoaConfig.put(ID, "success", v)),
			Setting.bool("recast", "Cast again", "Casts the rod again (after 0.6 to 1.6 seconds) once the line is reeled in.",
				() -> KhoaConfig.getBool(ID, "recast", true), v -> KhoaConfig.put(ID, "recast", v)));
	}

	@Override
	protected void onToggle(Minecraft mc, boolean now) {
		reset();
	}

	private void reset() {
		hookId = -1;
		reelScheduled = false;
		castScheduled = false;
	}

	@Override
	protected void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gui.screen() != null || AutoEatFeature.isEating()) {
			return;
		}
		InteractionHand rod = player.getMainHandItem().is(Items.FISHING_ROD) ? InteractionHand.MAIN_HAND
			: player.getOffhandItem().is(Items.FISHING_ROD) ? InteractionHand.OFF_HAND : null;
		if (rod == null) {
			reset();
			return;
		}
		long now = System.nanoTime();
		ThreadLocalRandom random = ThreadLocalRandom.current();
		FishingHook hook = player.fishing;
		if (hook == null) {
			hookId = -1;
			reelScheduled = false;
			if (castScheduled && now - castAt >= 0) {
				castScheduled = false;
				use(mc, rod);
			}
			return;
		}
		if (hook.getId() != hookId) { // a new cast: decide now how well this one will go
			hookId = hook.getId();
			reelScheduled = false;
			castScheduled = false;
			int success = KhoaConfig.getInt(ID, "success", 95);
			if (random.nextInt(100) < success) {
				plan = Plan.NORMAL;
			} else {
				Plan[] mistakes = { Plan.MISS, Plan.LATE, Plan.EARLY };
				plan = mistakes[random.nextInt(mistakes.length)];
				if (plan == Plan.EARLY) {
					schedule(now + millis(random.nextLong(800, 4000)));
				}
			}
		}
		if (!reelScheduled && ((FishingHookAccessor) hook).khoa$isBiting()) {
			long delay = millis(random.nextLong(0, KhoaConfig.getInt(ID, "delay_ms", 300) + 1L));
			switch (plan) {
				case NORMAL -> schedule(now + delay);
				case LATE -> schedule(now + delay + millis(random.nextLong(1500, 3000)));
				case MISS -> schedule(now + millis(random.nextLong(3000, 6000))); // does not notice, pulls the line in much later
				case EARLY -> schedule(now + delay); // the early reel did not happen in time, so it is a normal catch
			}
		}
		if (reelScheduled && now - reelAt >= 0) {
			reelScheduled = false;
			use(mc, rod);
			if (KhoaConfig.getBool(ID, "recast", true)) {
				castScheduled = true;
				castAt = now + millis(random.nextLong(600, 1600));
			}
		}
	}

	private void schedule(long at) {
		reelScheduled = true;
		reelAt = at;
	}

	private static long millis(long ms) {
		return ms * 1_000_000L;
	}

	private static void use(Minecraft mc, InteractionHand hand) {
		if (mc.gameMode.useItem(mc.player, hand).consumesAction()) {
			mc.player.swing(hand, SwingAnimation.DEFAULT, true);
		}
	}
}
