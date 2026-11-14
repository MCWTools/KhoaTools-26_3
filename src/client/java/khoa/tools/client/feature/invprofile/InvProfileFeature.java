package khoa.tools.client.feature.invprofile;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.setting.Setting;

/**
 * Inventory Profiles: saved layouts for the whole inventory (armor, offhand, hotbar, inventory). A slot asks for an exact item or for the best item
 * of a kind (Sword, Pickaxe ...). Loading arranges the inventory step by step, limited to a number of steps per second.
 * The card opens the profile list; /khoatoolsclient invprofile [load <name> | stop | list].
 */
public final class InvProfileFeature implements KhoaFeature {
	private static final String ID = "invprofile";

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Inventory Profiles";
	}

	@Override
	public String description() {
		return "Click the card to open your inventory profiles. Create one, press Edit and choose for each slot an exact item or a kind of item "
			+ "(Sword, Pickaxe, Axe, Shovel, Hoe, Helmet, Chestplate, Leggings, Boots, Bow, Crossbow, Shield: the best one you have by material, enchantments and damage; "
			+ "digging tools by mining speed). Double click a profile to load it: items move into place from your inventory (the stack with the most items for an exact item), "
			+ "whatever was in the way goes into the inventory, a worse armor piece is taken off, empty profile slots are left alone and the offhand is only filled when it is empty. "
			+ "Commands: /khoatoolsclient invprofile load <name>, stop, list.";
	}

	@Override
	public List<Setting> settings() {
		return List.of(Setting.slider("steps_per_second", "Steps per second", "How many inventory clicks per second a load may use.", 1, 40,
			() -> KhoaConfig.getInt(ID, "steps_per_second", 9), v -> KhoaConfig.put(ID, "steps_per_second", v)));
	}

	@Override
	public boolean isActive() {
		return InvProfileLoader.isLoading();
	}

	@Override
	public void activate(Minecraft mc) {
		mc.gui.setScreen(new InvProfileListScreen(mc.gui.screen()));
	}

	@Override
	public void onInit() {
		ClientTickEvents.END_CLIENT_TICK.register(InvProfileLoader::tick);
	}

	@Override
	public void registerCommands(LiteralArgumentBuilder<FabricClientCommandSource> root, CommandBuildContext context) {
		root.then(ClientCommands.literal(ID)
			.executes(ctx -> {
				Minecraft mc = ctx.getSource().getClient();
				mc.execute(() -> activate(mc)); // after the chat screen has closed
				return 1;
			})
			.then(ClientCommands.literal("list").executes(ctx -> {
				List<InvProfile> all = InvProfileStore.all();
				KhoaFeature.say(ctx.getSource().getClient(), all.isEmpty() ? "No inventory profiles yet."
					: "Inventory profiles: " + String.join(", ", all.stream().map(p -> p.name).toList()));
				return 1;
			}))
			.then(ClientCommands.literal("stop").executes(ctx -> {
				InvProfileLoader.stop(ctx.getSource().getClient(), "stopped.");
				return 1;
			}))
			.then(ClientCommands.literal("load")
				.then(ClientCommands.argument("name", StringArgumentType.string())
					.suggests((ctx, builder) -> {
						List<String> names = new ArrayList<>();
						for (InvProfile p : InvProfileStore.all()) {
							names.add(p.name.contains(" ") ? "\"" + p.name + "\"" : p.name);
						}
						return SharedSuggestionProvider.suggest(names, builder);
					})
					.executes(ctx -> {
						Minecraft mc = ctx.getSource().getClient();
						String name = StringArgumentType.getString(ctx, "name");
						InvProfile p = InvProfileStore.findByName(name);
						if (p == null) {
							KhoaFeature.say(mc, "No inventory profile named '" + name + "'.");
							return 0;
						}
						InvProfileLoader.start(mc, p);
						return 1;
					}))));
	}
}
