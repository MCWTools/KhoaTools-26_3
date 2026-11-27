package khoa.tools.setting;

/**
 * Extra options of one picked entry ("Advanced Setting" in the picker). Every field may be null, meaning "default":
 * {@code minY} = bottom of the world, {@code maxY} = height limit of the world, {@code rgb} = the feature's own overlay colour.
 * Defaults stay null so a pick follows the dimension you are in.
 */
public record PickAdvanced(Integer minY, Integer maxY, Integer rgb) {
	public static final PickAdvanced DEFAULT = new PickAdvanced(null, null, null);

	public boolean isDefault() {
		return minY == null && maxY == null && rgb == null;
	}

	/** Is the block layer {@code y} inside the range (both ends included)? */
	public boolean allowsY(int y) {
		return (minY == null || y >= minY) && (maxY == null || y <= maxY);
	}

	/** Where {@link #setting} rows keep the options, keyed like the picked list ("kind:namespace:id"). */
	public interface Store {
		PickAdvanced advanced(String key);

		void setAdvanced(String key, PickAdvanced value);
	}
}
