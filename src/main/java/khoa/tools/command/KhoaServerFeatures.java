package khoa.tools.command;

import java.util.List;

public final class KhoaServerFeatures {
	private static final List<KhoaServerFeature> ALL = List.of(
		new OptimizeTntFeature(),
		new FarMobAiFeature(),
		new ShieldBreakCdFeature()
	);

	private KhoaServerFeatures() {
	}

	public static List<KhoaServerFeature> all() {
		return ALL;
	}
}
