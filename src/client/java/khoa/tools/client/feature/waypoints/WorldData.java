package khoa.tools.client.feature.waypoints;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The waypoints and groups of one server / world, with the group tree logic. Call {@link #refresh()} after changing the lists directly. */
public final class WorldData {
	/** Bumped on every structural / visibility change; render caches compare it. */
	private static int changes;

	public final List<Waypoint> waypoints = new ArrayList<>();
	public final List<Group> groups = new ArrayList<>();

	private final Map<String, Waypoint> wpById = new HashMap<>();
	private final Map<String, Group> groupById = new HashMap<>();
	/** child id (waypoint or group) -> id of the group it is in. Missing = top level. */
	private final Map<String, String> parent = new HashMap<>();

	public static int changes() {
		return changes;
	}

	/** Something visible changed (a toggle) without the lists changing. */
	public static void touch() {
		changes++;
	}

	public Waypoint waypoint(String id) {
		return wpById.get(id);
	}

	public Group group(String id) {
		return groupById.get(id);
	}

	/** Id of the group directly containing this waypoint / group, or null at top level. */
	public String parentOf(String id) {
		return parent.get(id);
	}

	/** Rebuilds the lookup tables, drops dangling ids, makes sure everything has one parent, breaks cycles and ranks the death points. */
	public void refresh() {
		wpById.clear();
		groupById.clear();
		for (Waypoint w : waypoints) {
			w.migrate();
			wpById.put(w.id, w);
		}
		for (Group g : groups) {
			groupById.put(g.id, g);
		}
		parent.clear();
		for (Group g : groups) {
			g.waypoints.removeIf(id -> !wpById.containsKey(id) || parent.putIfAbsent(id, g.id) != null);
			g.groups.removeIf(id -> id.equals(g.id) || !groupById.containsKey(id) || parent.putIfAbsent(id, g.id) != null);
		}
		for (Group g : groups) {
			String p = parent.get(g.id);
			for (int guard = 0; p != null && guard < 10000; guard++) {
				if (p.equals(g.id)) { // a group inside itself: cut it loose
					Group owner = groupById.get(parent.get(g.id));
					if (owner != null) {
						owner.groups.remove(g.id);
					}
					parent.remove(g.id);
					break;
				}
				p = parent.get(p);
			}
		}
		// latest / old death are counted separately in every dimension
		java.util.Map<String, List<Waypoint>> deathsByDim = new java.util.HashMap<>();
		for (Waypoint w : waypoints) {
			if (w.death) {
				deathsByDim.computeIfAbsent(w.dim, k -> new ArrayList<>()).add(w);
			}
		}
		for (List<Waypoint> deaths : deathsByDim.values()) {
			deaths.sort(Comparator.comparingLong((Waypoint w) -> w.created).reversed());
			for (int i = 0; i < deaths.size(); i++) {
				deaths.get(i).deathRank = i;
			}
		}
		changes++;
	}

	// ------------------------------------------------------------------ visibility

	/** True when the group and every group above it is on. */
	public boolean groupChainVisible(String groupId) {
		String id = groupId;
		for (int guard = 0; id != null && guard < 10000; guard++) {
			Group g = groupById.get(id);
			if (g != null && !g.visible) {
				return false;
			}
			id = parent.get(id);
		}
		return true;
	}

	/** On itself and in no hidden group. */
	public boolean visible(Waypoint w) {
		return w.visible && groupChainVisible(parent.get(w.id));
	}

	public boolean visible(Group g) {
		return g.visible && groupChainVisible(parent.get(g.id));
	}

	// ------------------------------------------------------------------ tree queries

	/** True when {@code id} is inside group {@code ancestorId} (at any depth). */
	public boolean isInside(String id, String ancestorId) {
		String p = parent.get(id);
		for (int guard = 0; p != null && guard < 10000; guard++) {
			if (p.equals(ancestorId)) {
				return true;
			}
			p = parent.get(p);
		}
		return false;
	}

	public List<Waypoint> descendantWaypoints(Group g) {
		List<Waypoint> out = new ArrayList<>();
		collect(g, out, new HashSet<>());
		return out;
	}

	private void collect(Group g, List<Waypoint> out, Set<String> seen) {
		if (!seen.add(g.id)) {
			return;
		}
		for (String id : g.waypoints) {
			Waypoint w = wpById.get(id);
			if (w != null) {
				out.add(w);
			}
		}
		for (String id : g.groups) {
			Group child = groupById.get(id);
			if (child != null) {
				collect(child, out, seen);
			}
		}
	}

	private void collectGroups(Group g, List<Group> out) {
		for (String id : g.groups) {
			Group child = groupById.get(id);
			if (child != null) {
				out.add(child);
				collectGroups(child, out);
			}
		}
	}

	// ------------------------------------------------------------------ editing

	/** Moves waypoints / groups into a group (null = top level). Groups cannot go into themselves or into something inside them. */
	public int move(Collection<String> ids, String target) {
		int moved = 0;
		for (String id : new ArrayList<>(ids)) {
			boolean isGroup = groupById.containsKey(id);
			if (!isGroup && !wpById.containsKey(id)) {
				continue;
			}
			if (isGroup && target != null && (target.equals(id) || isInside(target, id))) {
				continue;
			}
			if (java.util.Objects.equals(parent.get(id), target)) {
				continue;
			}
			String old = parent.get(id);
			if (old != null && groupById.containsKey(old)) {
				groupById.get(old).waypoints.remove(id);
				groupById.get(old).groups.remove(id);
			}
			if (target != null) {
				Group t = groupById.get(target);
				if (t == null) {
					continue;
				}
				(isGroup ? t.groups : t.waypoints).add(id);
				t.modified = System.currentTimeMillis();
			}
			parent.put(id, target); // refresh below rebuilds it from the lists
			moved++;
		}
		refresh();
		return moved;
	}

	/** New group placed in {@code container} (null = top level) holding the given waypoints / groups. */
	public Group createGroup(String name, Collection<String> ids, String container) {
		Group g = new Group();
		g.name = name;
		g.created = System.currentTimeMillis();
		g.modified = g.created;
		groups.add(g);
		refresh();
		if (container != null && groupById.containsKey(container)) {
			groupById.get(container).groups.add(g.id);
			refresh();
		}
		move(ids, g.id);
		return g;
	}

	/** Dissolves a group: what is inside moves to the group's parent. */
	public void unpack(String groupId) {
		Group g = groupById.get(groupId);
		if (g == null) {
			return;
		}
		String up = parent.get(groupId);
		List<String> inside = new ArrayList<>(g.groups);
		inside.addAll(g.waypoints);
		move(inside, up);
		Group again = groupById.get(groupId);
		if (again != null) {
			String owner = parent.get(groupId);
			if (owner != null && groupById.containsKey(owner)) {
				groupById.get(owner).groups.remove(groupId);
			}
			groups.remove(again);
		}
		refresh();
	}

	/** Deletes a group together with everything inside it. */
	public void deleteGroup(String groupId) {
		Group g = groupById.get(groupId);
		if (g == null) {
			return;
		}
		List<Group> doomed = new ArrayList<>();
		doomed.add(g);
		collectGroups(g, doomed);
		Set<String> doomedWaypoints = new HashSet<>();
		Set<String> doomedGroups = new HashSet<>();
		for (Group d : doomed) {
			doomedGroups.add(d.id);
			doomedWaypoints.addAll(d.waypoints);
		}
		waypoints.removeIf(w -> doomedWaypoints.contains(w.id));
		groups.removeIf(x -> doomedGroups.contains(x.id));
		refresh();
	}

	public void deleteWaypoints(Collection<String> ids) {
		Set<String> set = new HashSet<>(ids);
		waypoints.removeIf(w -> set.contains(w.id));
		refresh();
	}
}
