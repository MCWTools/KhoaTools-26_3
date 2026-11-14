package khoa.tools.client.feature.searchsuggestion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

import khoa.tools.KhoaTools;
import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.setting.Setting;

/**
 * Search Suggestion (client side): asks the server for ALL the suggestions of the next argument of a command (what the tab list would show
 * with nothing typed yet), then keeps the ones that contain each search word anywhere in their name and prints them in chat.
 * Normal tab completion only shows names that START with what you typed; this finds names with the word in the middle or at the end.
 * Set the command and the comma separated words in the config, then click the card (or the Search button).
 */
public final class SearchSuggestionFeature implements KhoaFeature {
	private static final String ID = "suggestionsearch";
	private static final int MAX_SHOWN = 300;
	private static boolean running;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Search Suggestion";
	}

	@Override
	public String description() {
		return "Finds command suggestions that contain a word anywhere in their name. Type the command (for example /pw) and the words to look for "
			+ "(separate with commas, for example xp, money) in the config, then click this card. The server's suggestions for the first argument after "
			+ "the command are all collected and the ones that contain each word are printed in chat. To search a later argument, type the command with "
			+ "the earlier arguments, for example /warp set.";
	}

	@Override
	public List<Setting> settings() {
		return List.of(
			Setting.string("command", "Command", "The command to ask suggestions for, e.g. /pw or /playerwarp.",
				() -> KhoaConfig.getString(ID, "command", "/pw"), v -> KhoaConfig.put(ID, "command", v)),
			Setting.string("words", "Search words", "Names must contain the word. Separate several words with commas, e.g. xp, money.",
				() -> KhoaConfig.getString(ID, "words", ""), v -> KhoaConfig.put(ID, "words", v)),
			Setting.action("search", "Search", "Same as clicking the card.", "Search", () -> activate(Minecraft.getInstance())));
	}

	/** /khoatoolsclient suggestionsearch ["command"] word1 word2 ... : every argument after the quoted command is one search word. */
	@Override
	public void registerCommands(LiteralArgumentBuilder<FabricClientCommandSource> root, CommandBuildContext context) {
		root.then(ClientCommands.literal(ID)
			.executes(ctx -> {
				activate(ctx.getSource().getClient());
				return 1;
			})
			.then(ClientCommands.argument("command", StringArgumentType.string())
				.then(ClientCommands.argument("words", StringArgumentType.greedyString()).executes(ctx -> {
					List<String> words = new ArrayList<>();
					for (String w : StringArgumentType.getString(ctx, "words").trim().split("[ \t]+")) {
						if (!w.isEmpty() && words.stream().noneMatch(x -> x.equalsIgnoreCase(w))) {
							words.add(w);
						}
					}
					search(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "command").trim(), words);
					return 1;
				}))));
	}

	@Override
	public void activate(Minecraft mc) {
		String command = KhoaConfig.getString(ID, "command", "/pw").trim();
		List<String> words = new ArrayList<>();
		for (String w : KhoaConfig.getString(ID, "words", "").split(",")) {
			String clean = w.trim();
			if (!clean.isEmpty() && words.stream().noneMatch(x -> x.equalsIgnoreCase(clean))) {
				words.add(clean);
			}
		}
		search(mc, command, words);
	}

	private static void search(Minecraft mc, String command, List<String> words) {
		if (mc.player == null || mc.getConnection() == null) {
			KhoaFeature.say(mc, "Search Suggestion: join a world or server first.");
			return;
		}
		if (command.startsWith("/")) {
			command = command.substring(1);
		}
		command = command.trim();
		if (command.isEmpty() || words.isEmpty()) {
			KhoaFeature.say(mc, "Search Suggestion: set the Command and the Search words in the config first.");
			return;
		}
		if (running) {
			KhoaFeature.say(mc, "Search Suggestion: still waiting for the server...");
			return;
		}
		running = true;
		final String shown = "/" + command;
		KhoaFeature.say(mc, "Search Suggestion: asking the server for the suggestions of " + shown + " ...");
		new Crawl(mc, command, shown, words).step();
	}

	/**
	 * One request at a time (the server only answers the newest request of a tick, and the client drops an older pending answer).
	 * The vanilla server keeps only the first 1000 suggestions of a reply (ServerCommandSuggestionsProvider.limitSuggestionCount) and has no "next page"
	 * packet, so when a reply is exactly 1000 long the crawl asks again with one more letter typed (a, b, c ... then aa, ab ...), which the server
	 * filters by prefix, until every reply is shorter than 1000.
	 */
	private static final class Crawl {
		private static final int CAP = 1000;
		private static final int MAX_DEPTH = 6;
		private static final int MAX_REQUESTS = 800;
		private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789_-.";
		private final Minecraft mc;
		private final String command;
		private final String shown;
		private final List<String> words;
		private final java.util.Set<String> names = new java.util.LinkedHashSet<>();
		private final java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(List.of(""));
		private int requests;
		private int failed;
		private boolean capped;
		private boolean truncated;

		Crawl(Minecraft mc, String command, String shown, List<String> words) {
			this.mc = mc;
			this.command = command;
			this.shown = shown;
			this.words = words;
		}

		void step() {
			ClientPacketListener connection = mc.getConnection();
			if (connection == null || mc.player == null) {
				running = false;
				return;
			}
			if (queue.isEmpty() || requests >= MAX_REQUESTS) {
				truncated = !queue.isEmpty();
				finish();
				return;
			}
			String prefix = queue.poll();
			requests++;
			CommandDispatcher<ClientSuggestionProvider> dispatcher = connection.getCommands();
			String input = command + " " + prefix;
			CompletableFuture<Suggestions> future;
			try {
				future = dispatcher.getCompletionSuggestions(dispatcher.parse(input, connection.getSuggestionsProvider()), input.length());
			} catch (RuntimeException e) {
				KhoaTools.LOGGER.warn("Search Suggestion failed to ask for suggestions", e);
				failed++;
				mc.execute(this::step);
				return;
			}
			future.orTimeout(10, TimeUnit.SECONDS).whenComplete((suggestions, error) -> mc.execute(() -> {
				if (error != null || suggestions == null) {
					failed++;
				} else {
					int before = names.size();
					for (Suggestion sg : suggestions.getList()) {
						names.add(sg.getText());
					}
					// a full reply may have been cut: ask for the names that continue this prefix (skip when the reply taught us nothing new)
					if (suggestions.getList().size() >= CAP && names.size() > before && prefix.length() < MAX_DEPTH) {
						capped = true;
						for (char c : ALPHABET.toCharArray()) {
							queue.add(prefix + c);
						}
					}
				}
				if (requests % 50 == 0) {
					KhoaFeature.say(mc, "Search Suggestion: " + requests + " requests, " + names.size() + " names so far ...");
				}
				step();
			}));
		}

		private void finish() {
			running = false;
			if (capped) {
				KhoaFeature.say(mc, "Search Suggestion: the server sends at most " + CAP + " suggestions per reply, so " + requests + " requests with longer prefixes were used.");
			}
			if (truncated || failed > 0) {
				KhoaFeature.say(mc, "Search Suggestion: incomplete" + (truncated ? " (stopped at " + MAX_REQUESTS + " requests)" : "")
					+ (failed > 0 ? " (" + failed + " requests got no answer)" : "") + ".");
			}
			report(mc, shown, new ArrayList<>(names), words);
		}
	}

	private static void report(Minecraft mc, String command, List<String> all, List<String> words) {
		if (all.isEmpty()) {
			KhoaFeature.say(mc, "Search Suggestion: " + command + " gave no suggestions (is the command spelled right, and does it suggest names?).");
			return;
		}
		KhoaFeature.say(mc, "Search Suggestion: " + all.size() + " suggestions from " + command + ".");
		for (String word : words) {
			String lower = word.toLowerCase(Locale.ROOT);
			List<String> found = new ArrayList<>();
			for (String name : all) {
				if (name.toLowerCase(Locale.ROOT).contains(lower)) {
					found.add(name);
				}
			}
			// nearest first: names that start with the word, then the word as early as possible, then shorter names
			found.sort(Comparator.comparingInt((String n) -> n.toLowerCase(Locale.ROOT).indexOf(lower)).thenComparingInt(String::length)
				.thenComparing(String.CASE_INSENSITIVE_ORDER));
			if (found.isEmpty()) {
				KhoaFeature.say(mc, "No suggestion contains \"" + word + "\".");
				continue;
			}
			String list = String.join(", ", found.subList(0, Math.min(found.size(), MAX_SHOWN)));
			String more = found.size() > MAX_SHOWN ? " ... (+" + (found.size() - MAX_SHOWN) + " more)" : "";
			KhoaFeature.say(mc, "Matching suggestion for \"" + word + "\" : " + list + more);
		}
	}
}
