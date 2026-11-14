package khoa.tools.client.feature.pw;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.setting.Setting;

/** PW Nearest: lists the saved pws (see PW Scanner) closest to the X Y Z typed in the config. */
public final class PwNearestFeature implements KhoaFeature {
	private static final String ID = "pwnearest";
	private static final int SHOWN = 5;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "PW Nearest";
	}

	@Override
	public String description() {
		return "Type the X Y Z you want to reach in the config, then click the card: it prints the saved pws (from PW Scanner) closest to that spot. "
			+ "Or use /khoatoolsclient pwnearest <x> <y> <z>. Only pws in your current dimension are listed.";
	}

	@Override
	public List<Setting> settings() {
		return List.of(
			Setting.string("x", "X", "Target X.", () -> KhoaConfig.getString(ID, "x", "0"), v -> KhoaConfig.put(ID, "x", v.trim())).withPasteHook(PwNearestFeature::pasteCoordinates),
			Setting.string("y", "Y", "Target Y.", () -> KhoaConfig.getString(ID, "y", "64"), v -> KhoaConfig.put(ID, "y", v.trim())).withPasteHook(PwNearestFeature::pasteCoordinates),
			Setting.string("z", "Z", "Target Z.", () -> KhoaConfig.getString(ID, "z", "0"), v -> KhoaConfig.put(ID, "z", v.trim())).withPasteHook(PwNearestFeature::pasteCoordinates),
			Setting.action("find", "Find", "Prints the closest pws to the X Y Z above in chat. Closes this screen.", "Find", () -> {
				Minecraft mc = Minecraft.getInstance();
				mc.gui.setScreen(null);
				activate(mc);
			}));
	}

	/** "123 12 52" pasted into X, Y or Z fills all three; "123 52" sets X and Z only. */
	private static boolean pasteCoordinates(String text) {
		int[] v = khoa.api.chat.ChatCoordinates.parse(text);
		if (v == null) {
			return false;
		}
		KhoaConfig.put(ID, "x", Integer.toString(v[0]));
		if (v.length == 3) {
			KhoaConfig.put(ID, "y", Integer.toString(v[1]));
			KhoaConfig.put(ID, "z", Integer.toString(v[2]));
		} else {
			KhoaConfig.put(ID, "z", Integer.toString(v[1]));
		}
		return true;
	}

	@Override
	public void activate(Minecraft mc) {
		try {
			double x = Double.parseDouble(KhoaConfig.getString(ID, "x", "0").trim());
			double y = Double.parseDouble(KhoaConfig.getString(ID, "y", "64").trim());
			double z = Double.parseDouble(KhoaConfig.getString(ID, "z", "0").trim());
			KhoaFeature.say(mc, find(mc, x, y, z));
		} catch (NumberFormatException e) {
			KhoaFeature.say(mc, "PW Nearest: X, Y, Z trong Config phải là số.");
		}
	}

	@Override
	public void registerCommands(LiteralArgumentBuilder<FabricClientCommandSource> root, CommandBuildContext context) {
		root.then(ClientCommands.literal(ID)
			.executes(ctx -> {
				activate(ctx.getSource().getClient());
				return 1;
			})
			.then(ClientCommands.argument("x", DoubleArgumentType.doubleArg())
				.then(ClientCommands.argument("y", DoubleArgumentType.doubleArg())
					.then(ClientCommands.argument("z", DoubleArgumentType.doubleArg()).executes(ctx -> {
						double x = DoubleArgumentType.getDouble(ctx, "x");
						double y = DoubleArgumentType.getDouble(ctx, "y");
						double z = DoubleArgumentType.getDouble(ctx, "z");
						ctx.getSource().sendFeedback(Component.literal(find(ctx.getSource().getClient(), x, y, z)));
						return 1;
					})))));
	}

	private static String find(Minecraft mc, double x, double y, double z) {
		String dim = mc.level != null ? mc.level.dimension().identifier().toString() : null;
		List<PwDatabase.Entry> list = new ArrayList<>();
		for (PwDatabase.Entry e : PwDatabase.all(mc, PwDatabase.OK)) {
			if (dim == null || dim.equals(e.dim)) {
				list.add(e);
			}
		}
		if (list.isEmpty()) {
			return "PW Nearest: chưa có pw nào được lưu ở đây (chạy PW Scanner trước).";
		}
		list.sort(Comparator.comparingDouble(e -> dist(e, x, y, z)));
		StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "PW gần (%d %d %d) nhất (trong %d pw):", Math.round(x), Math.round(y), Math.round(z), list.size()));
		for (int i = 0; i < Math.min(SHOWN, list.size()); i++) {
			PwDatabase.Entry e = list.get(i);
			sb.append(String.format(Locale.ROOT, "%n %d. /pw %s  Coord: %d %d %d  (cách %d block)", i + 1, e.name, Math.round(e.x), Math.round(e.y), Math.round(e.z),
				Math.round(dist(e, x, y, z))));
		}
		return sb.toString();
	}

	private static double dist(PwDatabase.Entry e, double x, double y, double z) {
		double dx = e.x - x;
		double dy = e.y - y;
		double dz = e.z - z;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
