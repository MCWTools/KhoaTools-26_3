package khoa.tools.client.feature.bettersummon;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /bettersummon <amount> <what /summon takes>}: runs {@code /summon <rest>} amount times, e.g.
 * {@code /bettersummon 20 zombie ~ ~ ~ {NoAI:1b}}. Singleplayer only: the command is hidden (requires fails) on any server, and it runs on the
 * integrated server with the player's own command source, so the world's normal permissions (cheats) apply and no chat packets are sent
 * (no spam kick). The command text is parsed once, so a typo is reported once instead of amount times.
 */
public final class BetterSummonCommand {
	public static final int MAX_AMOUNT = 10000;

	private BetterSummonCommand() {
	}

	public static void init() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(ClientCommands.literal("bettersummon")
			.requires(source -> Minecraft.getInstance().hasSingleplayerServer())
			.then(ClientCommands.argument("amount", IntegerArgumentType.integer(1, MAX_AMOUNT))
				.then(ClientCommands.argument("summon", StringArgumentType.greedyString()).executes(c -> {
					Minecraft mc = Minecraft.getInstance();
					int amount = IntegerArgumentType.getInteger(c, "amount");
					String command = "summon " + StringArgumentType.getString(c, "summon").trim();
					MinecraftServer server = mc.getSingleplayerServer();
					if (server == null || mc.player == null) {
						c.getSource().sendError(Component.literal("/bettersummon only works in singleplayer"));
						return 0;
					}
					java.util.UUID id = mc.player.getUUID();
					server.execute(() -> run(server, id, command, amount));
					return 1;
				})))));
	}

	/** Server thread. */
	private static void run(MinecraftServer server, java.util.UUID playerId, String command, int amount) {
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		if (player == null) {
			return;
		}
		CommandSourceStack source = player.createCommandSourceStack();
		ParseResults<CommandSourceStack> parsed = server.getCommands().getDispatcher().parse(command, source);
		if (!parsed.getExceptions().isEmpty() || parsed.getReader().canRead() || parsed.getContext().getNodes().size() < 2) {
			String why = parsed.getExceptions().isEmpty() ? "Incomplete or unknown /summon arguments" : parsed.getExceptions().values().iterator().next().getMessage();
			source.sendFailure(Component.literal("/bettersummon: " + why));
			return;
		}
		for (int i = 0; i < amount; i++) {
			server.getCommands().performCommand(parsed, command);
		}
		source.sendSuccess(() -> Component.literal("Ran /" + command + " x" + amount), false);
	}
}
