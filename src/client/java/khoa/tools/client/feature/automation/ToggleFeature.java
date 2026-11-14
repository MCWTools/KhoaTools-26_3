package khoa.tools.client.feature.automation;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.Minecraft;

import khoa.tools.client.feature.KhoaFeature;

/** A card that turns a per-tick automation on and off; the state is saved (persistEnabled). */
abstract class ToggleFeature implements KhoaFeature {
	protected boolean on;

	@Override
	public boolean isActive() {
		return on;
	}

	@Override
	public boolean persistEnabled() {
		return true;
	}

	@Override
	public void activate(Minecraft mc) {
		on = !on;
		onToggle(mc, on);
		KhoaFeature.say(mc, displayName() + ": " + (on ? "on" : "off"));
	}

	/** Called after the state changed (clean up when it was turned off). */
	protected void onToggle(Minecraft mc, boolean now) {
	}

	/** Turns itself off with a reason in chat. */
	protected void pause(Minecraft mc, String reason) {
		on = false;
		onToggle(mc, false);
		KhoaFeature.say(mc, displayName() + " paused: " + reason);
	}

	/** True = run at the start of the client tick (before the game handles the keys), false = at the end. */
	protected boolean atStart() {
		return false;
	}

	@Override
	public void onInit() {
		if (atStart()) {
			ClientTickEvents.START_CLIENT_TICK.register(this::run);
		} else {
			ClientTickEvents.END_CLIENT_TICK.register(this::run);
		}
	}

	private void run(Minecraft mc) {
		if (on) {
			tick(mc);
		}
	}

	protected abstract void tick(Minecraft mc);
}
