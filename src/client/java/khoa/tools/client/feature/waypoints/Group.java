package khoa.tools.client.feature.waypoints;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A group of waypoints and/or other groups (Groups.json). It only stores ids: the waypoints themselves live in Waypoints.json.
 * Turning a group off hides everything inside it, recursively.
 */
public final class Group {
	public String id = UUID.randomUUID().toString();
	public String name = "Group";
	public boolean visible = true;
	public long created;
	public long modified;
	/** Ids of the waypoints directly inside. */
	public List<String> waypoints = new ArrayList<>();
	/** Ids of the groups directly inside. */
	public List<String> groups = new ArrayList<>();
}
