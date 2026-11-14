package khoa.tools.client.feature.pw;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.setting.Setting;

/**
 * PW Scanner (made for one server): asks the server for the suggestions of "/pw <prefix>" (the server only sends 1000 at a time, so a
 * prefix narrows it), then runs "/pw <name>" for each new name, waits a few seconds, and saves where it ended up in
 * config/khoatools-pw.json. A pw that was already saved is never tried again. If a GUI opens (payment...) it is only closed, nothing is
 * clicked, nothing is saved (the player did not teleport), and the scan goes on to the next pw at once.
 */
public final class PwScanFeature implements KhoaFeature {
	private static final String ID = "pwscan";
	/** Suggestion request ids of this feature (the vanilla chat uses small numbers). */
	private static final int ID_BASE = 7_770_000;
	/** A moved player counts as teleported from this distance (blocks). */
	private static final double MIN_MOVE = 2.0;

	private enum Phase { FETCH, NEXT, WAIT }

	private static boolean running;
	private static Phase phase = Phase.NEXT;
	private static int requestId = ID_BASE;
	private static volatile Suggestions fetched;
	private static final Deque<String> queue = new ArrayDeque<>();
	private static final Set<String> triedThisRun = new HashSet<>();
	private static String current;
	private static String command;
	private static int ticks;
	private static int total;
	private static int saved;
	private static int skippedGui;
	private static int noTp;
	private static double preX;
	private static double preY;
	private static double preZ;
	private static String preDim;
	private static volatile boolean retryRequested;
	private static int retries;
	private static boolean savedThis;
	private static int deadTicks;
	private static int skippedDied;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "PW Scanner";
	}

	@Override
	public String description() {
		return "Tries a batch of the /pw suggestions (Scan amount, spread evenly over the untried ones; run again for the next batch), waits N seconds after each and saves where you ended up in config/khoatools-pw.json, "
			+ "so it never tries the same pw again. The server shows only 1000 suggestions at a time: type a Prefix to search for more. "
			+ "If a payment GUI (a container menu) opens it is closed without clicking anything, nothing is saved and the next pw is tried at once. "
			+ "Click the card to start/stop, or type /khoatoolsclient pwscan stop. Use together with PW Nearest.";
	}

	@Override
	public List<Setting> settings() {
		return List.of(
			Setting.string("command", "Command", "The warp command without the slash, e.g. pw.", () -> KhoaConfig.getString(ID, "command", "pw"),
				value -> KhoaConfig.put(ID, "command", value.trim())),
			Setting.string("prefix", "Prefix", "Only pws whose name starts with this are fetched (empty = the first 1000). Run again with another prefix to find more.",
				() -> KhoaConfig.getString(ID, "prefix", ""), value -> KhoaConfig.put(ID, "prefix", value.trim())),
			Setting.integer("amount", "Scan amount", "How many pws to try per run, spread evenly over the untried ones. Run again for the next batch (never repeats).", 1, 1000,
				() -> KhoaConfig.getInt(ID, "amount", 10), value -> KhoaConfig.put(ID, "amount", Math.max(1, Math.min(1000, value)))),
			Setting.decimal("wait", "Delay before saving coord (s)", "Seconds after each /pw before your position is read and saved (decimals ok, e.g. 3.5).", 0.1, 60,
				PwScanFeature::waitSeconds, value -> KhoaConfig.putDouble(ID, "wait", value)),
			Setting.decimal("interval", "TP interval (s)", "Seconds from one /pw to the next. Never shorter than the delay above.", 0.1, 120,
				PwScanFeature::intervalSeconds, value -> KhoaConfig.putDouble(ID, "interval", value)),
			Setting.string("retry", "Retry text", "If a chat message contains any of these texts while waiting (comma-separated, e.g. 'not safe, cancelled' from 'Teleport destination is not safe' / 'Teleportation cancelled'), the command is sent again (up to 2 times). Empty = off.",
				() -> KhoaConfig.getString(ID, "retry", "not safe, cancelled"), value -> KhoaConfig.put(ID, "retry", value.trim())),
			Setting.action("start", "Scan", "Starts (or stops) the scan with the Prefix above. Closes this screen.", "Start / Stop", () -> {
				Minecraft mc = Minecraft.getInstance();
				mc.gui.setScreen(null);
				toggle(mc, KhoaConfig.getString(ID, "prefix", ""));
			}));
	}

	@Override
	public boolean isActive() {
		return running;
	}

	@Override
	public void activate(Minecraft mc) {
		toggle(mc, KhoaConfig.getString(ID, "prefix", ""));
	}

	@Override
	public void onInit() {
		ClientTickEvents.END_CLIENT_TICK.register(PwScanFeature::tick);
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> onMessage(message.getString()));
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> onMessage(message.getString()));
	}

	/** The server asked to run the command again (for example "Teleport destination is not safe. Re-write the command..."). */
	private static void onMessage(String text) {
		if (running && phase == Phase.WAIT) {
			if (matchesRetry(text)) {
				retryRequested = true;
			}
		}
	}

	/** True when the text contains any of the comma-separated retry keywords (case-insensitive). */
	static boolean matchesRetry(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		for (String key : KhoaConfig.getString(ID, "retry", "not safe, cancelled").split(",")) {
			String k = key.trim().toLowerCase(Locale.ROOT);
			if (!k.isEmpty() && lower.contains(k)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void registerCommands(LiteralArgumentBuilder<FabricClientCommandSource> root, CommandBuildContext context) {
		root.then(ClientCommands.literal(ID)
			.executes(ctx -> {
				toggle(ctx.getSource().getClient(), KhoaConfig.getString(ID, "prefix", ""));
				return 1;
			})
			.then(ClientCommands.literal("stop").executes(ctx -> {
				stop(ctx.getSource().getClient(), "đã dừng");
				return 1;
			}))
			.then(ClientCommands.literal("list").executes(ctx -> {
				Minecraft mc = ctx.getSource().getClient();
				ctx.getSource().sendFeedback(Component.literal(String.format(Locale.ROOT, "PW: %d có tọa độ, %d không tp, %d bị GUI chặn",
					PwDatabase.all(mc, PwDatabase.OK).size(), PwDatabase.all(mc, PwDatabase.NO_TP).size(), PwDatabase.all(mc, PwDatabase.GUI).size())));
				return 1;
			}))
			.then(ClientCommands.literal("cleargui").executes(ctx -> {
				ctx.getSource().sendFeedback(Component.literal("PW: đã xóa " + PwDatabase.clearStatus(ctx.getSource().getClient(), PwDatabase.GUI) + " mục bị đánh dấu GUI"));
				return 1;
			}))
			.then(ClientCommands.literal("clear").executes(ctx -> {
				ctx.getSource().sendFeedback(Component.literal("PW: đã xóa " + PwDatabase.clear(ctx.getSource().getClient()) + " mục của server này"));
				return 1;
			}))
			.then(ClientCommands.argument("prefix", StringArgumentType.word()).executes(ctx -> {
				start(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "prefix"));
				return 1;
			})));
	}

	// ------------------------------------------------------------ control

	private static void toggle(Minecraft mc, String prefix) {
		if (running) {
			stop(mc, "đã dừng");
		} else {
			start(mc, prefix);
		}
	}

	private static void start(Minecraft mc, String prefix) {
		if (mc.player == null || mc.getConnection() == null) {
			return;
		}
		command = KhoaConfig.getString(ID, "command", "pw").trim();
		if (command.startsWith("/")) {
			command = command.substring(1);
		}
		if (command.isEmpty()) {
			KhoaFeature.say(mc, "PW Scanner: nhập Command trong Config trước.");
			return;
		}
		if (mc.gui.screen() != null) {
			mc.gui.setScreen(null);
		}
		running = true;
		queue.clear();
		triedThisRun.clear();
		total = 0;
		saved = 0;
		skippedGui = 0;
		skippedDied = 0;
		noTp = 0;
		fetched = null;
		ticks = 0;
		phase = Phase.FETCH;
		requestId++;
		mc.getConnection().send(new ServerboundCommandSuggestionPacket(requestId, "/" + command + " " + prefix));
		KhoaFeature.say(mc, "PW Scanner: đang lấy danh sách /" + command + " " + prefix + " ...");
	}

	private static void stop(Minecraft mc, String why) {
		if (!running) {
			return;
		}
		running = false;
		queue.clear();
		current = null;
		KhoaFeature.say(mc, String.format(Locale.ROOT, "PW Scanner: %s. Đã lưu %d, không tp %d, GUI chặn %d, chết %d.", why, saved, noTp, skippedGui, skippedDied));
	}

	/** Called by the mixin (main thread) for every suggestions packet. */
	public static void onSuggestions(int id, Suggestions suggestions) {
		if (running && phase == Phase.FETCH && id == requestId) {
			fetched = suggestions;
		}
	}

	// ------------------------------------------------------------ tick

	private static void tick(Minecraft mc) {
		if (!running) {
			return;
		}
		LocalPlayer player = mc.player;
		if (player == null || mc.getConnection() == null) {
			running = false;
			queue.clear();
			return;
		}
		if (player.isDeadOrDying()) {
			// Died during the scan: respawn at once. A pw whose coordinate was not saved yet is skipped for good.
			if (phase == Phase.WAIT && !savedThis) {
				PwDatabase.put(mc, current, PwDatabase.DIED, preDim, 0, 0, 0);
				skippedDied++;
				KhoaFeature.say(mc, "PW Scanner: chết khi thử " + current + " -> bỏ qua (" + remaining() + ")");
				phase = Phase.NEXT;
			}
			if (deadTicks++ % 10 == 0) {
				player.respawn();
			}
			return;
		}
		deadTicks = 0;
		switch (phase) {
			case FETCH -> {
				Suggestions s = fetched;
				if (s != null) {
					// Names not tried yet, then Scan amount of them spread evenly over that list. The next run picks again from what is
					// still untried, so it never repeats and stays spread out.
					List<String> untried = new java.util.ArrayList<>();
					for (Suggestion sug : s.getList()) {
						String name = sug.getText();
						if (!name.isBlank() && !PwDatabase.isDone(mc, name) && triedThisRun.add(name.toLowerCase(Locale.ROOT))) {
							untried.add(name);
						}
					}
					int amount = Math.max(1, KhoaConfig.getInt(ID, "amount", 10));
					int pick = Math.min(amount, untried.size());
					for (int i = 0; i < pick; i++) {
						queue.add(untried.get((int) (((i + 0.5) * untried.size()) / pick)));
					}
					total = pick;
					KhoaFeature.say(mc, String.format(Locale.ROOT, "PW Scanner: server trả %d gợi ý, %d chưa thử, sẽ thử %d cái rải đều (mỗi cái %.1fs).",
						s.getList().size(), untried.size(), pick, Math.max(waitSeconds(), intervalSeconds())));
					phase = Phase.NEXT;
				} else if (++ticks > 100) {
					stop(mc, "server không trả gợi ý (thử lại hoặc kiểm tra Command)");
				}
			}
			case NEXT -> {
				current = queue.poll();
				if (current == null) {
					stop(mc, "xong");
					return;
				}
				preX = player.getX();
				preY = player.getY();
				preZ = player.getZ();
				preDim = mc.level.dimension().identifier().toString();
				ticks = 0;
				retries = 0;
				retryRequested = false;
				savedThis = false;
				phase = Phase.WAIT;
				mc.getConnection().sendCommand(command + " " + current);
			}
			case WAIT -> {
				ticks++;
				if (retryRequested) {
					retryRequested = false;
					if (!savedThis && retries < 2) {
						retries++;
						ticks = 0;
						mc.getConnection().sendCommand(command + " " + current); // "not safe, write the command again"
						return;
					}
				}
				// Only a container menu counts (the payment GUI). Your own inventory / pause / chat screens are left alone.
				if (player.containerMenu != player.inventoryMenu) {
					// payment / confirmation GUI: only close it, never click. The player did not teleport, so nothing is saved.
					player.closeContainer();
					mc.gui.setScreen(null);
					if (!savedThis) {
						PwDatabase.put(mc, current, PwDatabase.GUI, preDim, 0, 0, 0);
						skippedGui++;
						KhoaFeature.say(mc, "PW Scanner: " + current + " mở GUI -> bỏ qua (" + remaining() + ")");
					}
					phase = Phase.NEXT;
					return;
				}
				// Two different times: the coordinate is read after the Delay; the next /pw is sent after the TP interval (never before the save).
				int delay = (int) Math.max(1, Math.round(waitSeconds() * 20));
				if (!savedThis && ticks >= delay) {
					finishCurrent(mc, player);
					savedThis = true;
				}
				if (ticks >= Math.max(delay, Math.round(intervalSeconds() * 20))) {
					phase = Phase.NEXT;
				}
			}
		}
	}

	private static void finishCurrent(Minecraft mc, LocalPlayer player) {
		String dim = mc.level.dimension().identifier().toString();
		double dx = player.getX() - preX;
		double dy = player.getY() - preY;
		double dz = player.getZ() - preZ;
		boolean moved = !dim.equals(preDim) || Math.sqrt(dx * dx + dy * dy + dz * dz) >= MIN_MOVE;
		if (moved) {
			PwDatabase.put(mc, current, PwDatabase.OK, dim, player.getX(), player.getY(), player.getZ());
			saved++;
			KhoaFeature.say(mc, String.format(Locale.ROOT, "PW %s  Coord: %d %d %d  (%s)", current, Math.round(player.getX()), Math.round(player.getY()),
				Math.round(player.getZ()), remaining()));
		} else {
			PwDatabase.put(mc, current, PwDatabase.NO_TP, dim, 0, 0, 0);
			noTp++;
			KhoaFeature.say(mc, "PW Scanner: " + current + " không teleport -> ghi là không tp (" + remaining() + ")");
		}
	}

	/** True while a scan is running (PW Recorder then ignores the commands the scan sends). */
	public static boolean scanning() {
		return running;
	}

	/** Delay before the coordinate is read. */
	private static double waitSeconds() {
		return Math.max(0.1, KhoaConfig.getDouble(ID, "wait", 4));
	}

	/** Time from one /pw to the next (at least the delay). */
	private static double intervalSeconds() {
		return Math.max(0.1, KhoaConfig.getDouble(ID, "interval", 6));
	}

	private static String remaining() {
		return "còn " + queue.size() + "/" + total;
	}
}
