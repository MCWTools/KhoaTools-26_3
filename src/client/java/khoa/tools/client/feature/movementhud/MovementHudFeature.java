package khoa.tools.client.feature.movementhud;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import khoa.api.hud.HudPanel;
import khoa.api.hud.KhoaHud;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.setting.Setting;

/**
 * Movement HUD: two editable texts drawn by KhoaAPILibs ({@link KhoaHud}): the velocity of the player and a state line
 * (Sprinting / Walking / Standing) for people who play with the FOV effects at 0% and cannot see the sprint FOV change.
 * The card toggles the HUD; "Edit HUD" in the config hides the KhoaTools GUI and opens the library's drag / scale / visible editor.
 */
public final class MovementHudFeature implements KhoaFeature {
	private static final String ID = "movementhud";
	/** Horizontal speed (blocks per tick) below which the player counts as standing. */
	private static final double STAND_EPSILON = 0.005;

	private static boolean on;
	private static HudPanel panel;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Movement HUD";
	}

	@Override
	public String description() {
		return "Shows your velocity (X / Y / Z in blocks per second) and whether you are Sprinting, Walking or Standing. "
			+ "Click Edit HUD to drag the texts anywhere, change their scale or untick Visible.";
	}

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
		KhoaFeature.say(mc, "Movement HUD: " + (on ? "on" : "off"));
	}

	@Override
	public List<Setting> settings() {
		return List.of(Setting.action("edit", "HUD layout",
			"Hides this menu and opens the editor: drag a text, change its scale, untick Visible to hide it (it stays faintly visible in the editor so you can bring it back). Esc returns here.",
			"Edit HUD", () -> panel().openEditor()));
	}

	@Override
	public void onInit() {
		panel();
	}

	private static synchronized HudPanel panel() {
		if (panel == null) {
			panel = KhoaHud.panel("khoatools.movementhud").activeWhen(() -> on);
			panel.add("velocity", "Velocity", MovementHudFeature::velocityText, 0.02, 0.30);
			panel.add("state", "Movement state", MovementHudFeature::stateText, 0.02, 0.30 + 0.12);
		}
		return panel;
	}

	/** Blocks per second, from how far the player really moved during the last tick. */
	private static String velocityText() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) {
			return "";
		}
		return String.format(Locale.ROOT, "Velocity X: %.2f\nVelocity Y: %.2f\nVelocity Z: %.2f",
			(p.getX() - p.xo) * 20, (p.getY() - p.yo) * 20, (p.getZ() - p.zo) * 20);
	}

	private static String stateText() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) {
			return "";
		}
		if (p.isSprinting()) {
			return "Sprinting";
		}
		double dx = p.getX() - p.xo;
		double dz = p.getZ() - p.zo;
		return Math.sqrt(dx * dx + dz * dz) > STAND_EPSILON ? "Walking" : "Standing";
	}
}
