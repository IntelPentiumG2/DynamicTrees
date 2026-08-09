package com.dtteam.dynamictrees.tree;

import com.dtteam.dynamictrees.DynamicTrees;
import com.dtteam.dynamictrees.api.network.MapSignal;
import com.dtteam.dynamictrees.block.branch.BasicRootsBlock;
import com.dtteam.dynamictrees.block.branch.BranchBlock;
import com.dtteam.dynamictrees.config.DTConfigs;
import com.dtteam.dynamictrees.systems.nodemapper.CollectorNode;
import com.dtteam.dynamictrees.tree.family.Family;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Finds and removes branch networks that can no longer reach any {@link com.dtteam.dynamictrees.block.soil.SoilBlock}.
 *
 * <p>Nothing inside Dynamic Trees produces these. They appear when something else deletes the soil out from
 * under a finished tree without a neighbour update -- late-running terrain carvers, {@code /fill}, world
 * editors. {@link com.dtteam.dynamictrees.block.soil.SoilBlock} has no removal hook, and a branch never asks
 * on its own whether it is still attached, so the canopy simply hangs there forever.</p>
 *
 * <p>This is the automatic counterpart to {@code /dt clearorphaned}, and it is deliberately more cautious
 * than the command: it acts only when a walk <em>completes</em> and reaches no soil. A walk that overflowed
 * or found a tangled multi-root network is treated as inconclusive and left alone, because the only
 * available remedy is irreversible.</p>
 */
public final class OrphanValidator {

    /** When the sweep runs. */
    public enum Mode {
        /** Never. */
        OFF,
        /** Only for chunks the server has just generated. */
        GENERATED_ONLY,
        /** Every chunk load, which also retrofits worlds damaged before this ran. */
        ALL_LOADS
    }

    private static final int SECTION_SIZE = 16;

    /**
     * Largest neighbourhood we are willing to require before sweeping a chunk. A walk can reach
     * {@link Family#getMaxSignalDepth()} blocks away from its seed, and every one of those reads goes
     * through {@link Level#getBlockState}, which generates on a miss. If some family wants a deeper walk
     * than this, the sweep disables itself rather than risk driving chunk generation from a level tick.
     */
    private static final int MAX_GATE_RADIUS = 6;

    /**
     * Pending chunks per dimension, in arrival order.
     *
     * <p>A chunk stays queued until it either gets swept or unloads. There is deliberately no timeout: a
     * chunk sitting at the edge of the loaded area may wait indefinitely for its outer neighbours, and
     * those are exactly the chunks a late-running carver is still going to reach into. Entries are dropped
     * when the chunk is gone, so the queue is bounded by the chunks actually resident.</p>
     */
    private static final Map<ResourceKey<Level>, LinkedHashSet<Long>> QUEUES = new HashMap<>();

    private static boolean warnedUnsafeDepth = false;

    private OrphanValidator() {
    }

    /**
     * The configured mode, or {@link Mode#GENERATED_ONLY} if the config has not loaded yet. Queuing a chunk
     * we later decide not to sweep is free; dropping one we should have swept is not, and there is no
     * second chance at it.
     */
    public static Mode mode() {
        return DTConfigs.SERVER_CONFIG.isLoaded() ? DTConfigs.SERVER.orphanTreeCleanup.get() : Mode.GENERATED_ONLY;
    }

    /**
     * Queues a chunk for validation. The chunk is not swept now: the damage we are looking for is usually
     * done by a <em>neighbouring</em> chunk generating later, so sweeping at load time would run too early.
     */
    public static void enqueue(ServerLevel level, ChunkPos chunkPos) {
        QUEUES.computeIfAbsent(level.dimension(), key -> new LinkedHashSet<>()).add(chunkPos.pack());
    }

    public static void unload(Level level) {
        QUEUES.remove(level.dimension());
    }

    /**
     * Drains part of the queue for one level. Call once per level tick.
     */
    public static void process(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        final LinkedHashSet<Long> queue = QUEUES.get(serverLevel.dimension());
        if (queue == null || queue.isEmpty()) {
            return;
        }

        if (!DTConfigs.SERVER_CONFIG.isLoaded()) {
            return; // Hold the queue rather than discarding it; we cannot know the mode yet.
        }

        if (mode() == Mode.OFF) {
            queue.clear();
            return;
        }

        final int gateRadius = gateRadius();
        if (gateRadius < 0) { // A family wants a deeper walk than we can safely gate for.
            queue.clear();
            return;
        }

        // Snapshot first: a deferred chunk goes back on the end of the queue, and we must not retry it in
        // the same tick.
        final int budget = Math.min(DTConfigs.SERVER.orphanSweepChunksPerTick.get(), queue.size());
        final long[] batch = new long[budget];
        int batchSize = 0;
        for (long key : queue) {
            batch[batchSize++] = key;
            if (batchSize == budget) {
                break;
            }
        }

        for (int i = 0; i < batchSize; i++) {
            final long key = batch[i];
            if (!queue.remove(key)) {
                continue;
            }

            final ChunkPos chunkPos = ChunkPos.unpack(key);
            final LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
            if (chunk == null) {
                continue; // Unloaded before we got to it. It will be re-queued if it comes back.
            }

            if (!neighbourhoodLoaded(serverLevel, chunkPos, gateRadius)) {
                queue.add(key); // Back of the queue; retry once its neighbours have arrived.
                continue;
            }

            validateChunk(serverLevel, chunk);
        }
    }

    /**
     * Sweeps one chunk. Assumes the surrounding neighbourhood is already loaded -- see
     * {@link #neighbourhoodLoaded}.
     *
     * @return how many orphaned networks were destroyed
     */
    public static int validateChunk(ServerLevel level, LevelChunk chunk) {
        final LevelChunkSection[] sections = chunk.getSections();
        final int minBlockX = chunk.getPos().getMinBlockX();
        final int minBlockZ = chunk.getPos().getMinBlockZ();

        // Shared across the whole chunk: every branch a completed walk touched is already accounted for,
        // so later seeds in the same network are skipped instead of re-walked.
        final Set<BlockPos> proven = new HashSet<>();
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int cleared = 0;

        for (int index = 0; index < sections.length; index++) {
            final LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(OrphanValidator::isSweepSeed)) {
                continue; // Palette says this section cannot contain a branch. Skip 4096 lookups.
            }

            final int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));

            for (int y = 0; y < SECTION_SIZE; y++) {
                for (int z = 0; z < SECTION_SIZE; z++) {
                    for (int x = 0; x < SECTION_SIZE; x++) {
                        final BlockState state = section.getBlockState(x, y, z);
                        if (!isSweepSeed(state)) {
                            continue;
                        }

                        cursor.set(minBlockX + x, baseY + y, minBlockZ + z);
                        if (proven.contains(cursor)) {
                            continue;
                        }

                        if (validateNetwork(level, cursor.immutable(), (BranchBlock) state.getBlock(), proven)) {
                            cleared++;
                        }
                    }
                }
            }
        }

        if (cleared > 0) {
            DynamicTrees.LOG.debug("Removed {} orphaned tree network(s) from chunk {}", cleared, chunk.getPos());
        }

        return cleared;
    }

    /**
     * @return true if the network was found to be orphaned and destroyed
     */
    private static boolean validateNetwork(ServerLevel level, BlockPos pos, BranchBlock branch, Set<BlockPos> proven) {
        final MapSignal signal = new MapSignal(new CollectorNode(proven));

        // The default breaks a branch when the walk runs past the family's max signal depth. That is a
        // reasonable way to sever a genuine loop when the player asked for it, but this sweep runs
        // unattended, so the probe stays read-only.
        signal.destroyLoopedNodes = false;

        // Deliberately NOT setting trackVisited. BasicBranchBlock#analyse returns early on an
        // already-visited node without setting foundRoot or overflow, so a walk that entered a
        // depth-truncated part of a network it had already seen would look rootless -- and we would
        // destroy a healthy tree. The `proven` set is used only to skip start positions, never to prune
        // mid-walk.
        branch.analyse(level.getBlockState(pos), level, pos, null, signal);

        // Act only on a definitive answer. Overflow means the walk gave up before it could reach soil,
        // and multiroot means the network is tangled; both are inconclusive. /dt clearorphaned is still
        // available for those cases, where a player is watching.
        if (signal.foundRoot || signal.overflow || signal.multiroot) {
            return false;
        }

        ChunkTreeHelper.destroyTreeNetwork(level, branch, pos);
        return true;
    }

    /**
     * Aerial roots also extend {@link BranchBlock} but reach their soil through a different path and have
     * their own teardown rules, so the unattended sweep leaves them to the explicit command.
     */
    private static boolean isSweepSeed(BlockState state) {
        return state.getBlock() instanceof BranchBlock && !(state.getBlock() instanceof BasicRootsBlock);
    }

    private static boolean neighbourhoodLoaded(ServerLevel level, ChunkPos chunkPos, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                // getChunkNow never generates and never blocks; a null means "not here yet", so we wait.
                if (level.getChunkSource().getChunkNow(chunkPos.x() + dx, chunkPos.z() + dz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * How far a walk can stray from its seed, in chunks. Recomputed per drain rather than cached, because a
     * datapack reload can change a family's signal depth and getting this too small is exactly the mistake
     * that would let the sweep force chunk generation.
     *
     * @return the radius, or -1 if no radius we are willing to require would be enough
     */
    private static int gateRadius() {
        int deepest = 0;
        for (Family family : Family.REGISTRY.getAll()) {
            deepest = Math.max(deepest, family.getMaxSignalDepth());
        }

        final int required = Math.max(1, Mth.ceil(deepest / (float) SECTION_SIZE));
        if (required > MAX_GATE_RADIUS) {
            if (!warnedUnsafeDepth) {
                warnedUnsafeDepth = true;
                DynamicTrees.LOG.warn(
                        "Disabling the automatic orphaned-tree sweep: a tree family allows a signal depth of {} blocks, " +
                                "which would need a {}-chunk neighbourhood to check safely (limit {}). " +
                                "Use /dt clearorphaned instead.",
                        deepest, required, MAX_GATE_RADIUS);
            }
            return -1;
        }

        return required;
    }

}
