package khoa.tools.client.feature.illegal.spawnerfinder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fallback when no walkable path exists: finds the route to a spot next to the spawner that needs the fewest blocks mined.
 *
 * Search space: the player's feet cell, moving one block sideways with a step of -1 / 0 / +1 in height, or straight down (a tunnel that is
 * 1 wide and 2 high, like digging by hand). Every cell the body has to pass through that is solid costs extra, so the
 * route follows caves / tunnels / air where it can and only cuts through rock where that saves digging. Bedrock-like blocks
 * and lava are never entered, hard blocks (obsidian...) and cells touching lava cost more. Loaded chunks only.
 *
 * It is a weighted A*, so the result is close to the cheapest route rather than guaranteed optimal; this keeps far spawners fast.
 */
public final class SpawnerDigPath {
    public record Result(List<BlockPos> route, List<BlockPos> mine) {
    }

    private static final byte SOLID = 1;      // has collision: has to be mined to pass
    private static final byte BLOCKED = 2;    // never entered (lava, fire, unbreakable, unloaded chunk, the spawner itself)
    private static final byte FLOOR = 4;      // safe to stand on
    private static final byte HARD = 8;       // slow to mine
    private static final byte LAVA_NEAR = 16; // touches lava: mining it can flood the tunnel

    private static final float STEP_COST = 1.0f;
    private static final float DIG_COST = 3.0f;
    private static final float HARD_EXTRA = 6.0f;
    private static final float LAVA_NEAR_EXTRA = 8.0f;
    private static final float HEURISTIC_WEIGHT = 2.0f;
    private static final int MARGIN = 20;
    private static final int MAX_EXPANSIONS = 120_000;

    /** Four sideways moves, plus {0, 0} = a vertical shaft (only combined with a step down). */
    private static final int[][] MOVES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {0, 0}};

    private static final class Node {
        final long key;
        final int x, y, z;
        final float g, f;
        final Node parent;

        Node(long key, int x, int y, int z, float g, float f, Node parent) {
            this.key = key;
            this.x = x;
            this.y = y;
            this.z = z;
            this.g = g;
            this.f = f;
            this.parent = parent;
        }
    }

    private final Level level;
    private final BlockPos spawner;
    private final Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap();
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

    private SpawnerDigPath(Level level, BlockPos spawner) {
        this.level = level;
        this.spawner = spawner;
        this.cache.defaultReturnValue((byte) -1);
    }

    /** @return the route and the blocks to mine along it (empty list = nothing to dig), or null if none was found in the budget. */
    public static Result find(Level level, BlockPos start, BlockPos spawner) {
        return new SpawnerDigPath(level, spawner).search(start);
    }

    private Result search(BlockPos start) {
        int minX = Math.min(start.getX(), spawner.getX()) - MARGIN, maxX = Math.max(start.getX(), spawner.getX()) + MARGIN;
        int minY = Math.min(start.getY(), spawner.getY()) - MARGIN, maxY = Math.max(start.getY(), spawner.getY()) + MARGIN;
        int minZ = Math.min(start.getZ(), spawner.getZ()) - MARGIN, maxZ = Math.max(start.getZ(), spawner.getZ()) + MARGIN;

        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Float.compare(a.f, b.f));
        Long2ObjectOpenHashMap<Node> best = new Long2ObjectOpenHashMap<>();
        Node first = new Node(start.asLong(), start.getX(), start.getY(), start.getZ(), 0f,
                heuristic(start.getX(), start.getY(), start.getZ()), null);
        open.add(first);
        best.put(first.key, first);

        int[] cells = new int[9];
        int expanded = 0;
        while (!open.isEmpty()) {
            Node n = open.poll();
            if (best.get(n.key) != n) {
                continue; // a cheaper way to this cell was found after this entry was queued
            }
            if (isGoal(n.x, n.y, n.z)) {
                return build(n);
            }
            if (++expanded > MAX_EXPANSIONS) {
                return null;
            }
            for (int[] side : MOVES) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (side[0] == 0 && side[1] == 0 && dy != -1) {
                        continue; // straight up needs blocks to stand on, only straight down is a move
                    }
                    int nx = n.x + side[0], ny = n.y + dy, nz = n.z + side[1];
                    if (nx < minX || nx > maxX || ny < minY || ny > maxY || nz < minZ || nz > maxZ) {
                        continue;
                    }
                    int count = requiredCells(n.x, n.y, n.z, nx, ny, nz, cells);
                    float cost = STEP_COST;
                    boolean ok = true;
                    for (int i = 0; i < count; i += 3) {
                        float c = cellCost(cells[i], cells[i + 1], cells[i + 2]);
                        if (c < 0) {
                            ok = false;
                            break;
                        }
                        cost += c;
                    }
                    if (!ok || (info(nx, ny - 1, nz) & FLOOR) == 0) {
                        continue; // nothing safe to stand on there
                    }
                    long key = BlockPos.asLong(nx, ny, nz);
                    float g = n.g + cost;
                    Node old = best.get(key);
                    if (old != null && old.g <= g) {
                        continue;
                    }
                    Node next = new Node(key, nx, ny, nz, g, g + heuristic(nx, ny, nz), n);
                    best.put(key, next);
                    open.add(next);
                }
            }
        }
        return null;
    }

    /** Standing next to the spawner: it touches the feet cell or the head cell. */
    private boolean isGoal(int x, int y, int z) {
        int dx = Math.abs(spawner.getX() - x), dz = Math.abs(spawner.getZ() - z);
        int dyFeet = Math.abs(spawner.getY() - y), dyHead = Math.abs(spawner.getY() - (y + 1));
        return dx + dyFeet + dz == 1 || dx + dyHead + dz == 1;
    }

    private float heuristic(int x, int y, int z) {
        int d = Math.abs(spawner.getX() - x) + Math.abs(spawner.getY() - y) + Math.abs(spawner.getZ() - z);
        return HEURISTIC_WEIGHT * Math.max(0, d - 2);
    }

    /** The cells the body has to pass through for this step, as x,y,z triples. Returns how many ints were written. */
    private static int requiredCells(int x, int y, int z, int nx, int ny, int nz, int[] out) {
        int i = 0;
        if (nx == x && nz == z) {
            // straight down: the head is where the feet were, so only the cell you drop into can be in the way
            return put(out, i, nx, ny, nz);
        }
        if (ny == y) {
            i = put(out, i, nx, y, nz);
            i = put(out, i, nx, y + 1, nz);
        } else if (ny > y) {
            i = put(out, i, nx, y + 1, nz);
            i = put(out, i, nx, y + 2, nz);
            i = put(out, i, x, y + 2, z); // room above your head to step up
        } else {
            i = put(out, i, nx, y + 1, nz);
            i = put(out, i, nx, y, nz);
            i = put(out, i, nx, y - 1, nz);
        }
        return i;
    }

    private static int put(int[] out, int i, int x, int y, int z) {
        out[i] = x;
        out[i + 1] = y;
        out[i + 2] = z;
        return i + 3;
    }

    /** Extra cost of passing through this cell, or -1 if it can never be entered. */
    private float cellCost(int x, int y, int z) {
        byte b = info(x, y, z);
        if ((b & BLOCKED) != 0) {
            return -1f;
        }
        float cost = 0f;
        if ((b & SOLID) != 0) {
            cost += DIG_COST;
            if ((b & HARD) != 0) cost += HARD_EXTRA;
        }
        if ((b & LAVA_NEAR) != 0) cost += LAVA_NEAR_EXTRA;
        return cost;
    }

    private byte info(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        byte b = cache.get(key);
        if (b == -1) {
            b = classify(x, y, z);
            cache.put(key, b);
        }
        return b;
    }

    private byte classify(int x, int y, int z) {
        scratch.set(x, y, z);
        if (level.isOutsideBuildHeight(y) || !level.hasChunkAt(scratch) || scratch.equals(spawner)) {
            return BLOCKED;
        }
        BlockState s = level.getBlockState(scratch);
        if (s.getFluidState().typeHolder().is(FluidTags.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE)) {
            return BLOCKED;
        }
        byte b = 0;
        if (!s.getCollisionShape(level, scratch).isEmpty()) {
            float hardness = s.getDestroySpeed(level, scratch);
            if (hardness < 0) {
                return BLOCKED; // bedrock, barrier, ...
            }
            b |= SOLID;
            if (hardness >= 10f) b |= HARD;
            if (touchesLava(x, y, z)) b |= LAVA_NEAR;
        }
        if (s.isFaceSturdy(level, scratch, Direction.UP) && s.getFluidState().isEmpty()
                && !s.is(Blocks.MAGMA_BLOCK) && !s.is(Blocks.CACTUS)) {
            b |= FLOOR;
        }
        return b;
    }

    private boolean touchesLava(int x, int y, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            m.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
            if (level.hasChunkAt(m) && level.getBlockState(m).getFluidState().typeHolder().is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private Result build(Node goal) {
        List<Node> nodes = new ArrayList<>();
        for (Node n = goal; n != null; n = n.parent) {
            nodes.add(n);
        }
        java.util.Collections.reverse(nodes);

        List<BlockPos> route = new ArrayList<>(nodes.size());
        LinkedHashSet<Long> mine = new LinkedHashSet<>();
        int[] cells = new int[9];
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            route.add(new BlockPos(n.x, n.y, n.z));
            if (i == 0) {
                continue;
            }
            Node p = nodes.get(i - 1);
            int count = requiredCells(p.x, p.y, p.z, n.x, n.y, n.z, cells);
            for (int c = 0; c < count; c += 3) {
                if ((info(cells[c], cells[c + 1], cells[c + 2]) & SOLID) != 0) {
                    mine.add(BlockPos.asLong(cells[c], cells[c + 1], cells[c + 2]));
                }
            }
        }
        List<BlockPos> blocks = new ArrayList<>(mine.size());
        for (long l : mine) {
            blocks.add(BlockPos.of(l));
        }
        return new Result(route, blocks);
    }
}
