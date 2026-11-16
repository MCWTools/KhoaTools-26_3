package khoa.tools.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.Minecraft;

import khoa.tools.KhoaTools;
import khoa.tools.api.KhoaFeatureRegistry;
import khoa.tools.api.KhoaToolsAddon;
import khoa.tools.client.config.KhoaCheat;
import khoa.tools.client.config.KhoaCommandBinds;
import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.config.KhoaKeybinds;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.client.feature.KhoaFeatures;
import khoa.tools.client.gui.KhoaToolsScreen;
import khoa.tools.client.playlist.PlaylistManager;

public class KhoaToolsClient implements ClientModInitializer {
	/** Set by the commands; the screen is opened on the next tick so the chat screen can't close it again. */
	private static volatile boolean openGuiNextTick;

	/** Opens the KhoaTools GUI on the next client tick (same as /khoagui). */
	public static void requestOpenGui() {
		openGuiNextTick = true;
	}
	/** Controls screen -> KhoaTools -> "Open KhoaTools menu" (unbound by default). */
	private static net.minecraft.client.KeyMapping openGuiKey;

	@Override
	public void onInitializeClient() {
		KhoaConfig.load();
		openGuiKey = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(new net.minecraft.client.KeyMapping("key.khoatools.open_gui",
			com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD, com.mojang.blaze3d.platform.InputConstants.UNKNOWN.getValue(), khoa.tools.client.util.KeyCategory.get()));
		KhoaFeatureRegistry registry = new KhoaFeatureRegistry();
		for (var entrypoint : net.fabricmc.loader.api.FabricLoader.getInstance().getEntrypointContainers("khoatools-addon", KhoaToolsAddon.class)) {
			try { entrypoint.getEntrypoint().register(registry); }
			catch (Exception e) { KhoaTools.LOGGER.error("KhoaTools addon failed to register", e); }
		}

		for (KhoaFeature feature : KhoaFeatures.all()) {
			try {
				feature.onInit();
			} catch (Exception e) {
				KhoaTools.LOGGER.error("Feature {} failed to init", feature.id(), e);
			}
		}

		// Toggle features that opt in (persistEnabled): restore the saved on/off state, then save every change.
		java.util.Map<KhoaFeature, Boolean> lastEnabled = new java.util.HashMap<>();
		for (KhoaFeature feature : KhoaFeatures.all()) {
			if (!feature.persistEnabled() || KhoaCheat.isHidden(feature)) {
				continue;
			}
			try {
				if (KhoaConfig.getBool(feature.id(), "enabled", false) && !feature.isActive()) {
					feature.activate(Minecraft.getInstance());
				}
			} catch (Exception e) {
				KhoaTools.LOGGER.error("Feature {} failed to restore its state", feature.id(), e);
			}
			lastEnabled.put(feature, feature.isActive());
		}

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			for (java.util.Map.Entry<KhoaFeature, Boolean> e : lastEnabled.entrySet()) {
				boolean now = e.getKey().isActive();
				if (now != e.getValue()) {
					e.setValue(now);
					KhoaConfig.put(e.getKey().id(), "enabled", now);
				}
			}
			while (openGuiKey.consumeClick()) {
				if (client.gui.screen() == null) {
					openGuiNextTick = true;
				}
			}
			KhoaKeybinds.tick(client);
			PlaylistManager.tick();
			if (openGuiNextTick) {
				openGuiNextTick = false;
				client.gui.setScreen(new KhoaToolsScreen());
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
			// /khoatoolsclient = same GUI, plus one subcommand per feature (the old way, still works).
			LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal("khoatoolsclient")
				.executes(ctx -> {
					openGuiNextTick = true;
					return 1;
				});
			for (KhoaFeature feature : KhoaFeatures.all()) {
				registerGated(root, feature, context);
			}
			dispatcher.register(root);

			// /khoagui = opens the GUI, no arguments. (/khoatools is the server-side, op-only command and has no GUI.)
			// /khoagui cheat true|false is hidden: the argument has no suggestions and the word "cheat" is never listed.
			dispatcher.register(ClientCommands.literal("khoagui")
				.executes(ctx -> {
					openGuiNextTick = true;
					return 1;
				})
				.then(ClientCommands.argument("args", StringArgumentType.greedyString())
					.executes(ctx -> hiddenOption(ctx.getSource(), StringArgumentType.getString(ctx, "args")))));

			// Extra command names set in each feature's config screen (Command row) -> same command, same arguments.
			KhoaCommandBinds.registerAll(dispatcher);

			// /tick toggle (client side): freezes or unfreezes depending on the current state. Other /tick subcommands go to the server.
			khoa.tools.client.feature.TickToggleCommand.register(dispatcher);
		});
	}

	/**
	 * Registers the subcommands of a feature under /khoatoolsclient, usable (and suggested) only while the feature is not hidden (cheat switch or config/khoatools-hidden.txt).
	 * The requirement is read when Fabric copies the nodes into the suggestion tree and when the command is parsed, so
	 * {@link KhoaCheat#set} + refreshCommandCompletions() hides/shows them at once. The nodes stay registered, so command binds
	 * (aliases) keep pointing at them and come back with cheat true.
	 */
	private static void registerGated(LiteralArgumentBuilder<FabricClientCommandSource> root, KhoaFeature feature,
		net.minecraft.commands.CommandBuildContext context) {
		LiteralArgumentBuilder<FabricClientCommandSource> scratch = ClientCommands.literal("khoatoolsclient");
		feature.registerCommands(scratch, context);
		for (CommandNode<FabricClientCommandSource> node : scratch.getArguments()) {
			var gated = node.createBuilder()
				.requires(source -> !KhoaCheat.isHidden(feature) && node.canUse(source));
			for (CommandNode<FabricClientCommandSource> child : node.getChildren()) {
				gated.then(child);
			}
			root.then(gated);
		}
	}

	/** Handles "/khoagui <args>": only "cheat true|false" exists. */
	private static int hiddenOption(FabricClientCommandSource source, String args) {
		if (args.trim().equalsIgnoreCase("reload")) {
			KhoaCheat.reload();
			source.sendFeedback(net.minecraft.network.chat.Component.literal("KhoaTools hidden list reloaded"));
			return 1;
		}
		String[] parts = args.trim().toLowerCase(java.util.Locale.ROOT).split("\s+");
		if (parts.length == 2 && parts[0].equals("cheat") && (parts[1].equals("true") || parts[1].equals("false"))) {
			boolean value = Boolean.parseBoolean(parts[1]);
			KhoaCheat.set(value);
			source.sendFeedback(net.minecraft.network.chat.Component.literal("KhoaTools cheat: " + value));
			return 1;
		}
		source.sendFeedback(net.minecraft.network.chat.Component.literal("Usage: /khoagui"));
		return 0;
	}
}
