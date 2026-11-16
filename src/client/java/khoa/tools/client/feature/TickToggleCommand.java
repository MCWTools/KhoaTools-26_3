package khoa.tools.client.feature;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Client-side {@code /tick toggle}: reads the freeze state the server last sent to this client
 * (ClientboundTickingStatePacket -> ClientLevel.tickRateManager()) and runs the opposite vanilla command,
 * {@code /tick freeze} or {@code /tick unfreeze}.
 *
 * Only the {@code toggle} child lives on the client. Fabric hands every other {@code /tick ...} input to the server,
 * so vanilla subcommands keep working. The server still decides: without permission for /tick the toggle is rejected there.
 */
public final class TickToggleCommand {
	private TickToggleCommand() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommands.literal("tick")
			.then(ClientCommands.literal("toggle").executes(ctx -> toggle(ctx.getSource()))));
	}

	private static int toggle(FabricClientCommandSource source) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.getConnection() == null) {
			source.sendError(Component.literal("/tick toggle: not connected to a world."));
			return 0;
		}
		boolean frozen = mc.level.tickRateManager().isFrozen();
		String sub = frozen ? "unfreeze" : "freeze";
		mc.getConnection().sendCommand("tick " + sub);
		return 1;
	}
}
