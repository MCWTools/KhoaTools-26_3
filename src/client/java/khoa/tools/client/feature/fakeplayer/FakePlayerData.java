package khoa.tools.client.feature.fakeplayer;

/** Saved settings and frozen pose of one client-side fake player (stored per server + dimension). */
public final class FakePlayerData {
	public int id;
	/** Name tag. 1-16 characters, no spaces (same rule as a Minecraft account name). */
	public String name = "NPC";
	/** Empty = use the name as a player-name skin lookup. Otherwise a player name or the path of a .png file. */
	public String skin = "";
	public boolean slim;
	/** STANDING, CROUCHING, SWIMMING or FALL_FLYING. */
	public String pose = "STANDING";
	/** Items in vanilla item syntax, e.g. netherite_chestplate[enchantments={sharpness:5}]. Empty = nothing. */
	public String mainHand = "";
	public String offHand = "";
	public String head = "";
	public String chest = "";
	public String legs = "";
	public String feet = "";
	/** Comma separated effects, e.g. "speed:1, glowing". Only visual (particles / glowing / invisible). */
	public String effects = "";

	/** Copied hotbar (0-8) and inventory (9-35) in item syntax; counts are in {@link #inventoryCounts}. Empty = nothing stored. */
	public java.util.List<String> inventory = new java.util.ArrayList<>();
	public int[] inventoryCounts = new int[0];
	/** Hotbar slot (0-8) that was selected when the inventory was copied. */
	public int selectedSlot;
	/** When true, the teleport item also copies your armor and off hand to the fake player. */
	public boolean tpCopiesItems;
	/** Client-side simulation: gravity, collisions, 20 health, hurt / knockback / fall damage, death and respawn. */
	public boolean physics;

	public double x;
	public double y;
	public double z;
	public float yRot;
	public float xRot;
	public float headRot;
	public float bodyRot;
	public boolean sprinting;
	// frozen animation values, captured from the player when the teleport item is used
	public float walkPos;
	public float walkSpeed;
	public float swing;
	public float age;
}
