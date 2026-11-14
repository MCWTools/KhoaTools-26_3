package khoa.locator.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.TrackedWaypoint;

import khoa.tools.KhoaTools;
import khoa.tools.client.feature.KhoaFeature;

/**
 * Locator Coord: works out where a player is from several locator bar bearings taken at different places.
 * Each sample stores where you stood and the absolute yaw of the player's waypoint; two or more samples from different
 * spots give a point where the bearing lines cross (least squares when there are more than two). Samples are saved to
 * config/khoatools-locatorcoord.json until cleared.
 * <pre>
 * /locatorcoord                    sample the player nearest to your crosshair (or the Locator Finder target)
 * /locatorcoord &lt;player&gt;           sample that player
 * /locatorcoord &lt;player&gt; calculate
 * /locatorcoord &lt;player&gt; clear
 * </pre>
 */
public final class LocatorCoordFeature implements KhoaFeature {
	private static final String ID = "locatorcoord";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** sin^2 of the smallest useful angle between two bearings (about 6 degrees). */
	private static final double MIN_DET = 0.01;

	private static volatile List<String> suggestionNames = List.of();
	/** Players with saved samples who are not online; only suggested once typed in full. */
	private static volatile List<String> storedOfflineNames = List.of();
	private static int tick;
	private static Map<String, Entry> data;

	private static final class Sample {
		String dim;
		double x;
		double z;
		double yaw;
	}

	private static final class Entry {
		String name;
		List<Sample> samples = new ArrayList<>();
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Locator Coord";
	}

	@Override
	public String description() {
		return "Finds a player's coordinates from the locator bar by triangulation. Run it, walk far away (a different direction is best), run it again: "
			+ "two bearings are enough, more samples make it more exact. Clicking the card samples the player nearest to your crosshair (or the Locator Finder target). "
			+ "Commands: /locatorcoord <player> (add a sample), /locatorcoord <player> calculate, /locatorcoord <player> clear. Samples are kept until you clear them.";
	}

	@Override
	public void activate(Minecraft mc) {
		Target t = pickTarget(mc);
		if (t == null) {
			KhoaFeature.say(mc, "Locator Coord: không có player nào trên locator bar.");
			return;
		}
		KhoaFeature.say(mc, sample(mc, t.id, t.name));
	}

	@Override
	public void onInit() {
		// the short /locatorcoord ... (same code as /khoatoolsclient locatorcoord ...)
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> {
			if (dispatcher.getRoot().getChild(ID) == null) {
				dispatcher.getRoot().addChild(buildCommand(ID));
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null || client.getConnection() == null) {
				suggestionNames = List.of();
				tick = 0;
				return;
			}
			if (++tick % 20 == 0) {
				List<String> names = new ArrayList<>();
				UUID self = client.player.getUUID();
				for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
					if (!info.getProfile().id().equals(self)) {
						names.add(LocatorFinderClient.stripPrefix(info.getProfile().name()));
					}
				}
				List<String> stored = new ArrayList<>();
				String prefix = serverKey(client) + "|";
				for (Map.Entry<String, Entry> e : load().entrySet()) {
					if (e.getKey().startsWith(prefix) && !names.contains(e.getValue().name)) {
						stored.add(e.getValue().name);
					}
				}
				names.sort(String.CASE_INSENSITIVE_ORDER);
				suggestionNames = names;
				storedOfflineNames = stored;
			}
		});
	}

	@Override
	public void registerCommands(LiteralArgumentBuilder<FabricClientCommandSource> root, CommandBuildContext context) {
		// /khoatoolsclient locatorcoord ...
		root.then(buildCommand(ID));
	}

	private static LiteralCommandNode<FabricClientCommandSource> buildCommand(String literal) {
		LiteralCommandNode<FabricClientCommandSource> node = ClientCommands.literal(literal)
			.executes(ctx -> {
				Minecraft mc = ctx.getSource().getClient();
				Target t = pickTarget(mc);
				if (t == null) {
					ctx.getSource().sendError(Component.literal("Không có player nào trên locator bar."));
					return 0;
				}
				ctx.getSource().sendFeedback(Component.literal(sample(mc, t.id, t.name)));
				return 1;
			})
			.then(ClientCommands.argument("player", StringArgumentType.word())
				.suggests((c, b) -> {
					List<String> out = new ArrayList<>(suggestionNames);
					String typed = b.getRemaining();
					for (String n : storedOfflineNames) {
						if (n.equalsIgnoreCase(typed)) {
							out.add(n);
						}
					}
					return SharedSuggestionProvider.suggest(out, b);
				})
				.executes(ctx -> {
					Minecraft mc = ctx.getSource().getClient();
					String input = StringArgumentType.getString(ctx, "player");
					PlayerInfo info = findPlayer(mc, input);
					if (info == null) {
						ctx.getSource().sendError(Component.literal("Không thấy player '" + input + "' trong tab list"));
						return 0;
					}
					ctx.getSource().sendFeedback(Component.literal(sample(mc, info.getProfile().id(), info.getProfile().name())));
					return 1;
				})
				.then(ClientCommands.literal("calculate").executes(ctx -> {
					Minecraft mc = ctx.getSource().getClient();
					String input = StringArgumentType.getString(ctx, "player");
					ctx.getSource().sendFeedback(Component.literal(calculate(mc, input)));
					return 1;
				}))
				.then(ClientCommands.literal("clear").executes(ctx -> {
					Minecraft mc = ctx.getSource().getClient();
					String input = StringArgumentType.getString(ctx, "player");
					Map<String, Entry> map = load();
					Entry removed = map.remove(key(mc, input));
					save();
					ctx.getSource().sendFeedback(Component.literal(removed == null
						? "Locator Coord: không có dữ liệu của " + input
						: "Locator Coord: đã xóa " + removed.samples.size() + " mẫu của " + removed.name));
					return 1;
				})))
			.build();
		return node;
	}

	// ---------------------------------------------------------------- sampling

	private record Target(UUID id, String name) {
	}

	/** The Locator Finder target if one is tracked, else the locator bar player whose bearing is closest to the crosshair. */
	private static Target pickTarget(Minecraft mc) {
		if (mc.player == null || mc.getConnection() == null || mc.getCameraEntity() == null) {
			return null;
		}
		if (LocatorTracker.active()) {
			return new Target(LocatorTracker.targetId(), LocatorTracker.targetName());
		}
		Entity cam = mc.getCameraEntity();
		Level level = cam.level();
		Camera camera = mc.gameRenderer.mainCamera();
		float look = Mth.wrapDegrees(mc.player.getYRot());
		UUID[] best = new UUID[1];
		double[] bestDiff = {Double.MAX_VALUE};
		mc.getConnection().getWaypointManager().forEachWaypoint(cam, wp -> {
			UUID id = wp.id().left().orElse(null);
			if (id == null) {
				return;
			}
			double a = wp.yawAngleToCamera(level, camera, e -> 1.0F);
			if (Double.isNaN(a)) {
				return;
			}
			double diff = Math.abs(Mth.wrapDegrees(a + camera.yaw() - look));
			if (diff < bestDiff[0]) {
				bestDiff[0] = diff;
				best[0] = id;
			}
		});
		if (best[0] == null) {
			return null;
		}
		return new Target(best[0], nameOf(mc, best[0]));
	}

	private static String nameOf(Minecraft mc, UUID id) {
		PlayerInfo info = mc.getConnection().getPlayerInfo(id);
		return info != null ? info.getProfile().name() : id.toString();
	}

	/** Stores one sample for the player. Returns the chat message. */
	private static String sample(Minecraft mc, UUID id, String name) {
		if (mc.level == null || mc.getConnection() == null || mc.getCameraEntity() == null) {
			return "Chưa vào world.";
		}
		Entity cam = mc.getCameraEntity();
		Level level = cam.level();
		Camera camera = mc.gameRenderer.mainCamera();
		TrackedWaypoint[] found = new TrackedWaypoint[1];
		mc.getConnection().getWaypointManager().forEachWaypoint(cam, wp -> {
			if (wp.id().left().map(id::equals).orElse(false)) {
				found[0] = wp;
			}
		});
		if (found[0] == null) {
			return "Locator Coord: " + name + " không hiện trên locator bar (xa quá hoặc đã tắt waypoint).";
		}
		double angle = found[0].yawAngleToCamera(level, camera, e -> 1.0F);
		if (Double.isNaN(angle)) {
			return "Locator Coord: không đọc được hướng của " + name + ".";
		}
		Vec3 pos = camera.position();
		double yaw = Mth.wrapDegrees(angle + camera.yaw()); // + = to the right, so this is the world yaw towards the player

		Sample s = new Sample();
		s.dim = level.dimension().identifier().toString();
		s.x = pos.x;
		s.z = pos.z;
		s.yaw = yaw;
		Map<String, Entry> map = load();
		Entry entry = map.computeIfAbsent(key(mc, name), k -> new Entry());
		entry.name = LocatorFinderClient.stripPrefix(name);
		entry.samples.add(s);
		save();

		long here = entry.samples.stream().filter(o -> s.dim.equals(o.dim)).count();
		String msg = String.format(Locale.ROOT, "Locator Coord: %s mẫu #%d tại (%.1f, %.1f), hướng %.2f°", entry.name, entry.samples.size(), s.x, s.z, s.yaw);
		if (here >= 2) {
			msg += " | " + calculate(mc, name);
		} else {
			msg += " | đi xa rồi chạy lại để có mẫu thứ 2.";
		}
		return msg;
	}

	// ---------------------------------------------------------------- maths

	private static String calculate(Minecraft mc, String input) {
		Entry entry = load().get(key(mc, input));
		if (entry == null || entry.samples.isEmpty()) {
			return "Locator Coord: chưa có mẫu nào của " + input + ".";
		}
		String dim = mc.level != null ? mc.level.dimension().identifier().toString() : null;
		List<Sample> list = new ArrayList<>();
		for (Sample s : entry.samples) {
			if (dim == null || dim.equals(s.dim)) {
				list.add(s);
			}
		}
		if (list.size() < 2) {
			return "Locator Coord: cần ít nhất 2 mẫu trong dimension này (đang có " + list.size() + ").";
		}

		// Least squares point closest to all bearing lines: sum(I - d*d^T) * X = sum(I - d*d^T) * P, with d = (-sin yaw, cos yaw).
		double a00 = 0;
		double a01 = 0;
		double a11 = 0;
		double b0 = 0;
		double b1 = 0;
		double[] dx = new double[list.size()];
		double[] dz = new double[list.size()];
		for (int i = 0; i < list.size(); i++) {
			Sample s = list.get(i);
			double r = Math.toRadians(s.yaw);
			dx[i] = -Math.sin(r);
			dz[i] = Math.cos(r);
			double m00 = 1 - dx[i] * dx[i];
			double m01 = -dx[i] * dz[i];
			double m11 = 1 - dz[i] * dz[i];
			a00 += m00;
			a01 += m01;
			a11 += m11;
			b0 += m00 * s.x + m01 * s.z;
			b1 += m01 * s.x + m11 * s.z;
		}
		double det = a00 * a11 - a01 * a01;
		if (det < MIN_DET) {
			return "Locator Coord: các mẫu gần như cùng một đường thẳng, hãy lấy mẫu từ chỗ khác hướng (đi sang ngang rồi lấy lại).";
		}
		double x = (a11 * b0 - a01 * b1) / det;
		double z = (a00 * b1 - a01 * b0) / det;

		double maxPerp = 0;
		double sumDist = 0;
		int behind = 0;
		for (int i = 0; i < list.size(); i++) {
			Sample s = list.get(i);
			double rx = x - s.x;
			double rz = z - s.z;
			double along = rx * dx[i] + rz * dz[i];
			double perp = rx * dz[i] - rz * dx[i];
			maxPerp = Math.max(maxPerp, Math.abs(perp));
			sumDist += Math.hypot(rx, rz);
			if (along < 0) {
				behind++;
			}
		}
		// Range = radius around the point: the worst miss of a bearing line, or the error a 0.25 degree bearing error causes at this
		// distance with this crossing angle (two exact lines always meet, so the miss alone would say 0).
		double angular = (sumDist / list.size()) * Math.tan(Math.toRadians(0.25)) / Math.max(0.05, Math.sqrt(det) * 2 / list.size());
		double range = Math.max(maxPerp, angular);
		String msg = String.format(Locale.ROOT, "Triangulated location : %d %d , Range : %.1f block (%s, %d mẫu)",
			Math.round(x), Math.round(z), range, entry.name, list.size());
		if (behind > 0) {
			msg += " [cảnh báo: " + behind + " mẫu chỉ ngược hướng, kết quả có thể sai]";
		}
		return msg;
	}

	// ---------------------------------------------------------------- storage

	private static String serverKey(Minecraft mc) {
		return mc.getCurrentServer() != null ? mc.getCurrentServer().ip.toLowerCase(Locale.ROOT) : "singleplayer";
	}

	private static String key(Minecraft mc, String name) {
		return serverKey(mc) + "|" + LocatorFinderClient.stripPrefix(name).toLowerCase(Locale.ROOT);
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("khoatools-locatorcoord.json");
	}

	private static synchronized Map<String, Entry> load() {
		if (data == null) {
			data = new LinkedHashMap<>();
			Path f = file();
			if (Files.exists(f)) {
				try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
					Map<String, Entry> read = GSON.fromJson(r, new TypeToken<LinkedHashMap<String, Entry>>() { }.getType());
					if (read != null) {
						data = read;
					}
				} catch (IOException | RuntimeException e) {
					KhoaTools.LOGGER.error("Could not read " + f, e);
				}
			}
		}
		return data;
	}

	private static synchronized void save() {
		try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
			GSON.toJson(load(), w);
		} catch (IOException e) {
			KhoaTools.LOGGER.error("Could not write " + file(), e);
		}
	}

	private static PlayerInfo findPlayer(Minecraft mc, String input) {
		if (mc.getConnection() == null) {
			return null;
		}
		String wanted = LocatorFinderClient.stripPrefix(input);
		PlayerInfo prefix = null;
		for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
			String n = info.getProfile().name();
			if (n.equalsIgnoreCase(input)) {
				return info;
			}
			if (prefix == null && LocatorFinderClient.stripPrefix(n).equalsIgnoreCase(wanted)) {
				prefix = info;
			}
		}
		return prefix;
	}
}
