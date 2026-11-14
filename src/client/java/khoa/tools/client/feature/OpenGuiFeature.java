package khoa.tools.client.feature;

import net.minecraft.client.Minecraft;

import khoa.tools.client.KhoaToolsClient;

/** A card that only opens the KhoaTools GUI (same as /khoagui), so the GUI can get its own hotkey in the card's config screen. */
public final class OpenGuiFeature implements KhoaFeature {
	@Override
	public String id() {
		return "gui";
	}

	@Override
	public String displayName() {
		return "GUI";
	}

	@Override
	public String description() {
		return "Opens this KhoaTools GUI, the same as /khoagui. Set a hotkey or command for it in its config.";
	}

	@Override
	public java.util.List<khoa.tools.setting.Setting> settings() {
		return khoa.tools.client.gui.GuiAnim.settings();
	}

	@Override
	public void activate(Minecraft mc) {
		KhoaToolsClient.requestOpenGui();
	}
}
