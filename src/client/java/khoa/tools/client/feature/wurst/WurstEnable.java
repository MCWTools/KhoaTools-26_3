package khoa.tools.client.feature.wurst;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.network.chat.Component;

import khoa.tools.KhoaTools;

/**
 * {@code /khoatoolsclient enablewurst}: the same as Wurst's "Enable Wurst" button in the Statistics screen, for when that button is hidden
 * ("Hide enable button"). Wurst is optional, so it is reached through reflection. Hacks that were on before disabling stay off, as with the button.
 */
public final class WurstEnable {
	private WurstEnable() {
	}

	public static LiteralArgumentBuilder<FabricClientCommandSource> command() {
		return ClientCommands.literal("enablewurst").executes(ctx -> run(ctx.getSource()));
	}

	private static int run(FabricClientCommandSource source) {
		if (!FabricLoader.getInstance().isModLoaded("wurst")) {
			source.sendError(Component.literal("Wurst is not installed."));
			return 0;
		}
		try {
			Class<?> type = Class.forName("net.wurstclient.WurstClient");
			Object wurst = type.getField("INSTANCE").get(null);
			if ((boolean) type.getMethod("isEnabled").invoke(wurst)) {
				source.sendFeedback(Component.literal("Wurst is already enabled."));
				return 1;
			}
			type.getMethod("setEnabled", boolean.class).invoke(wurst, true);
			source.sendFeedback(Component.literal("Wurst enabled."));
			return 1;
		} catch (ReflectiveOperationException | LinkageError e) {
			KhoaTools.LOGGER.error("Could not enable Wurst", e);
			source.sendError(Component.literal("Could not enable Wurst: this Wurst version is not supported (see the log)."));
			return 0;
		}
	}
}
