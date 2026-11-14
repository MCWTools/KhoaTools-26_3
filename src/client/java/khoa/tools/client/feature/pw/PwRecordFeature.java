package khoa.tools.client.feature.pw;

import java.util.List;
import java.util.Locale;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.setting.Setting;

/**
 * PW Recorder: while on it does nothing by itself. It listens to the "/pw <name>" you type, waits Wait seconds, and if you really
 * teleported (and this pw is not in the database yet) saves where you ended up, exactly like PW Scanner. Nothing is saved when a GUI
 * opens, when the server says the destination is not safe (you re-write the command, which starts a new wait), or when you did not move.
 * The command name and the "not safe" text are shared with PW Scanner's config.
 */
public final class PwRecordFeature implements KhoaFeature {
	private static final String ID = "pwrecord";
	private static final String SCAN = "pwscan";
	private static final double MIN_MOVE = 2.0;

	private static boolean on;
	private static String pending;
	private static int ticks;
	private static double preX;
	private static double preY;
	private static double preZ;
	private static String preDim;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "PW Recorder";
	}

	@Override
	public String description() {
		return "Turn it on and just use /pw as normal. It watches the pw you teleport to, waits Wait seconds, and if the pw is not in the database yet "
			+ "it saves where you arrived (same file as PW Scanner, usable with PW Nearest). Nothing is saved if a GUI opens, the server says the destination is "
			+ "not safe, or you did not move. The command name and the 'not safe' text are taken from PW Scanner's config.";
	}

	@Override
	public List<Setting> settings() {
		return List.of(Setting.decimal("wait", "Wait seconds", "Seconds after your /pw before the position is saved (decimals ok, e.g. 3.5).", 0.1, 60,
			PwRecordFeature::waitSeconds, value -> KhoaConfig.putDouble(ID, "wait", value)));
	}

	@Override
	public boolean persistEnabled() {
		return true;
	}

	@Override
	public boolean isActive() {
		return on;
	}

	@Override
	public void activate(Minecraft mc) {
		on = !on;
		pending = null;
		KhoaFeature.say(mc, "PW Recorder: " + (on ? "on - cứ dùng /pw bình thường, mình sẽ ghi lại" : "off"));
	}

	@Override
	public void onInit() {
		ClientSendMessageEvents.COMMAND.register(PwRecordFeature::onCommand);
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> onMessage(message.getString()));
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> onMessage(message.getString()));
		ClientTickEvents.END_CLIENT_TICK.register(PwRecordFeature::tick);
	}

	private static double waitSeconds() {
		return Math.max(0.1, KhoaConfig.getDouble(ID, "wait", 4));
	}

	private static String pwCommand() {
		String c = KhoaConfig.getString(SCAN, "command", "pw").trim().toLowerCase(Locale.ROOT);
		return c.startsWith("/") ? c.substring(1) : c;
	}

	/** The player sent a command (no slash). Runs when the player types it, not for commands the scanner sends. */
	private static void onCommand(String command) {
		Minecraft mc = Minecraft.getInstance();
		if (!on || mc.player == null || mc.level == null || PwScanFeature.scanning()) {
			return;
		}
		String base = pwCommand();
		String text = command.strip();
		if (base.isEmpty() || !text.toLowerCase(Locale.ROOT).startsWith(base + " ")) {
			return;
		}
		String name = text.substring(base.length()).strip();
		if (name.isEmpty() || name.contains(" ")) {
			return;
		}
		if (PwDatabase.get(mc, name) != null) {
			pending = null;
			KhoaFeature.say(mc, "PW Recorder: " + name + " đã có trong database.");
			return;
		}
		pending = name;
		ticks = 0;
		LocalPlayer p = mc.player;
		preX = p.getX();
		preY = p.getY();
		preZ = p.getZ();
		preDim = mc.level.dimension().identifier().toString();
	}

	private static void onMessage(String text) {
		if (!on || pending == null) {
			return;
		}
		if (PwScanFeature.matchesRetry(text)) {
			pending = null; // not safe: you will write the command again, which starts a new wait
		}
	}

	private static void tick(Minecraft mc) {
		if (pending == null) {
			return;
		}
		LocalPlayer player = mc.player;
		if (!on || player == null || mc.level == null) {
			pending = null;
			return;
		}
		if (player.containerMenu != player.inventoryMenu) {
			pending = null; // a GUI (payment...): no teleport, nothing to save
			return;
		}
		if (++ticks < Math.max(1, Math.round(waitSeconds() * 20))) {
			return;
		}
		String name = pending;
		pending = null;
		String dim = mc.level.dimension().identifier().toString();
		double dx = player.getX() - preX;
		double dy = player.getY() - preY;
		double dz = player.getZ() - preZ;
		boolean moved = !dim.equals(preDim) || Math.sqrt(dx * dx + dy * dy + dz * dz) >= MIN_MOVE;
		if (moved && PwDatabase.get(mc, name) == null) {
			PwDatabase.put(mc, name, PwDatabase.OK, dim, player.getX(), player.getY(), player.getZ());
			KhoaFeature.say(mc, String.format(Locale.ROOT, "PW Recorder: %s  Coord: %d %d %d (đã lưu)", name, Math.round(player.getX()),
				Math.round(player.getY()), Math.round(player.getZ())));
		}
	}
}
