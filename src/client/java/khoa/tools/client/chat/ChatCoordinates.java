package khoa.tools.client.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** Adds a clipboard click action to coordinate spans while preserving the message's styling. */
public final class ChatCoordinates {
	private static final String NUM = "(?:[xyzXYZ]\\s*[:=]\\s*)?-?\\d+(?:\\.\\d+)?";
	private static final String SEP = "(?:\\s*,\\s*|\\s+)";
	/** 2 or 3 numbers split by spaces and/or commas, optionally in ( ) [ ] < > and/or labelled x: y: z: (decimals allowed). */
	private static final Pattern COORDINATES = Pattern.compile("(?<![\\w.+-])(?:[(\\[<]\\s*)?" + NUM + "(?:" + SEP + NUM + "){1,2}(?:\\s*[)\\]>])?(?!\\w|\\.\\d)");
	private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");
	/** What a click copies: "x y z" in whole numbers. */
	private static final Pattern NORMALIZED = Pattern.compile("-?\\d+ -?\\d+ -?\\d+");

	private ChatCoordinates() {
	}

	public static Component makeClickable(Component message) {
		String text = message.getString();
		if (text.isEmpty()) {
			return message;
		}

		List<Style> styles = new ArrayList<>(text.length());
		message.visit((style, fragment) -> {
			for (int i = 0; i < fragment.length(); i++) {
				styles.add(style);
			}
			return java.util.Optional.empty();
		}, Style.EMPTY);
		if (styles.size() != text.length()) {
			return message;
		}

		boolean[] clickable = new boolean[text.length()];
		String[] copies = new String[text.length()];
		Matcher matcher = COORDINATES.matcher(text);
		while (matcher.find()) {
			boolean hasClickAction = false;
			for (int i = matcher.start(); i < matcher.end(); i++) {
				hasClickAction |= styles.get(i).getClickEvent() != null;
			}
			if (hasClickAction) {
				continue;
			}
			String coordinate = normalize(matcher.group());
			if (coordinate == null) {
				continue;
			}
			for (int i = matcher.start(); i < matcher.end(); i++) {
				clickable[i] = true;
				copies[i] = coordinate;
			}
		}

		MutableComponent result = Component.empty();
		int start = 0;
		while (start < text.length()) {
			Style style = styles.get(start);
			if (clickable[start]) {
				style = style.withClickEvent(new ClickEvent.CopyToClipboard(copies[start]));
			}
			int end = start + 1;
			while (end < text.length() && styles.get(end).equals(styles.get(start))
				&& clickable[end] == clickable[start]
				&& (!clickable[start] || copies[end].equals(copies[start]))) {
				end++;
			}
			result.append(Component.literal(text.substring(start, end)).withStyle(style));
			start = end;
		}
		return result;
	}

	public static boolean isCoordinate(String value) {
		return NORMALIZED.matcher(value).matches();
	}

	/** Whole numbers of a coordinate text (decimals round down like block coordinates): {x, y, z}, or {x, z} when only two were given. Null if it is not one. */
	public static int[] parse(String text) {
		String trimmed = text.strip();
		if (!COORDINATES.matcher(trimmed).matches()) {
			return null;
		}
		List<Integer> values = new ArrayList<>();
		Matcher m = NUMBER.matcher(trimmed.replaceAll("[xyzXYZ]\\s*[:=]", " "));
		while (m.find()) {
			values.add((int) Math.floor(Double.parseDouble(m.group())));
		}
		if (values.size() < 2 || values.size() > 3) {
			return null;
		}
		return values.stream().mapToInt(Integer::intValue).toArray();
	}

	/** "x y z" for a click. Two numbers are X and Z, so Y is 0. */
	private static String normalize(String text) {
		int[] v = parse(text);
		if (v == null) {
			return null;
		}
		return v.length == 2 ? v[0] + " 0 " + v[1] : v[0] + " " + v[1] + " " + v[2];
	}
}
