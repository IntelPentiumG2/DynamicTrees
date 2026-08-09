package com.dtteam.dynamictrees.model.blockstate;

import com.dtteam.dynamictrees.api.network.Connections;
import com.dtteam.dynamictrees.block.branch.BranchBlock;
import com.dtteam.dynamictrees.block.branch.ThickBranchBlock;
import com.dtteam.dynamictrees.block.branch.TrunkShellBlock;
import com.dtteam.dynamictrees.model.BlockStateModelWithConnectionData;
import com.dtteam.dynamictrees.model.BranchMultiPartHolder;
import com.dtteam.dynamictrees.model.ModelConnections;
import com.dtteam.dynamictrees.model.ModelHelper;
import com.dtteam.dynamictrees.model.TrunkCellParts;
import com.dtteam.dynamictrees.model.parts.BranchModelPart;
import com.dtteam.dynamictrees.tree.TreeHelper;
import com.dtteam.dynamictrees.utility.CoordUtils;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public record ThickBranchBlockStateModel(
        BranchBlockStateModel fallback,
        BranchMultiPartHolder trunkBark,
        BranchMultiPartHolder trunkRings,
        TrunkCellParts cellBark,
        TrunkCellParts cellRings,
        TrunkCellParts coreBark,
        TrunkCellParts coreRings
) implements DynamicBlockStateModel, BlockStateModelWithConnectionData {

    @Override
    public @BakedQuad.MaterialFlags int materialFlags() {
        return fallback.materialFlags();
    }

    @Override
    public Material.Baked particleMaterial() {
        return fallback.particleMaterial();
    }

    @Override
    public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        int coreRadius = TreeHelper.getRadius(state);
        if (coreRadius <= BranchBlock.MAX_RADIUS) {
            return ModelHelper.getModelConnections(level, pos, state);
        }
        return geometryKey(this, level, pos, state);
    }

    /**
     * What a block's trunk geometry depends on, taken as the parts it draws.
     *
     * <p>Every part is a cached instance, one per cell, face and radius, so two blocks answer with
     * equal lists exactly when they draw the same trunk surface. Working it out any other way means
     * restating the rules {@link #collectCell} already applies, and getting that subtly wrong shows
     * up as a seam between two chunks rather than anywhere near the code that caused it.
     */
    static List<BlockStateModelPart> geometryKey(DynamicBlockStateModel model, BlockAndTintGetter level,
                                                 BlockPos pos, BlockState state) {
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(level, pos, state, KEY_RANDOM, parts);
        return parts;
    }

    /** Never drawn from — collectParts ignores it — so one shared instance avoids an allocation per block. */
    private static final RandomSource KEY_RANDOM = RandomSource.create(0);
    private static final Direction[] DIRECTIONS = Direction.values();

    @Override
    public void collectParts(BlockState state, List<BlockStateModelPart> parts, Connections connectionsData) {
        int coreRadius = TreeHelper.getRadius(state);
        if (coreRadius <= BranchBlock.MAX_RADIUS) {
            fallback.collectParts(state, parts, connectionsData);
            return;
        }
        coreRadius = Mth.clamp(coreRadius, BranchBlock.MAX_RADIUS + 1, ThickBranchBlock.MAX_RADIUS_THICK);

        // Clone: the connections object is the geometry cache key, so its array must stay untouched.
        final int[] connections = connectionsData.getAllRadii().clone();
        final Direction forceRingDir = ((ModelConnections) connectionsData).getRingOnly();
        final int twigRadius = ((ModelConnections) connectionsData).getFamily().getPrimaryThickness();

        int numConnections = BranchBlockStateModel.countConnections(connections);

        if (numConnections == 0 && forceRingDir != null) return;

        if (forceRingDir != null) {
            connections[forceRingDir.get3DDataValue()] = 0;
            parts.add(trunkRings.getPart(forceRingDir, coreRadius));
        }

        boolean branchesAround = areBranchesAround(connections);

        for (Direction face : DIRECTIONS) {
            gatherTrunkParts(parts, face, connections, twigRadius, branchesAround, coreRadius);
        }

    }

    private static boolean areBranchesAround(int[] connections) {
        return connections[2] + connections[3] + connections[4] + connections[5] != 0;
    }

    private void gatherTrunkParts(List<BlockStateModelPart> parts, Direction face, int[] connections, int twigRadius, boolean branchesAround, int coreRadius) {
        if (face == Direction.UP || face == Direction.DOWN) {
            if (connections[face.get3DDataValue()] < twigRadius && !branchesAround) {
                parts.add(this.trunkRings.getPart(face, coreRadius));
            } else if (connections[face.get3DDataValue()] < coreRadius) {
                parts.add(this.trunkBark.getPart(face, coreRadius));
            }
        } else {
            parts.add(trunkBark.getPart(face, coreRadius));
        }
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, List<BlockStateModelPart> parts) {
        int coreRadius = TreeHelper.getRadius(state);
        if (coreRadius <= BranchBlock.MAX_RADIUS) {
            ModelConnections connectionsData = ModelHelper.getModelConnections(level, pos, state);
            collectParts(state, parts, connectionsData);
            return;
        }
        int radius = Mth.clamp(coreRadius, BranchBlock.MAX_RADIUS + 1, ThickBranchBlock.MAX_RADIUS_THICK);

        collectCell(level, pos, radius, null, false, parts);

        // A shell draws the cell it occupies. Where none could be placed — the tree grew around a
        // surface root, a fruit, another branch — the core still has to draw that cell, or the
        // trunk is left with a hole and the cap of the slice above it hanging over the gap.
        for (CoordUtils.Surround cell : CoordUtils.Surround.values()) {
            if (!hasShell(level, pos, cell)) {
                collectCell(level, pos, radius, cell, true, parts);
            }
        }
    }

    /**
     * Draws the slice of trunk standing in one cell, with the faces its neighbours leave exposed.
     *
     * <p>Called by each trunk shell for the cell it occupies, and by the branch block at the core
     * for its own cell and for any the shells do not cover. {@code fromCore} says which of those it
     * is: a shell draws in its own block's space, the core reaches out of its block as the trunk
     * did when it was baked in one piece.
     *
     * <p>A face shared with another part of the same trunk is dropped rather than drawn and hidden.
     * Sideways that needs no lookup — a trunk this wide fills all nine cells, so a face is inside
     * the trunk exactly when the cell it faces is one of them. The two ends are capped with rings
     * unless a branch grows out of them, matching what the trunk did as one piece.
     */
    public void collectCell(BlockAndTintGetter level, BlockPos corePos, int radius,
                            @Nullable CoordUtils.Surround cell, boolean fromCore,
                            List<BlockStateModelPart> parts) {
        Vec3i offset = cell == null ? Vec3i.ZERO : cell.getOffset();
        TrunkCellParts bark = fromCore ? coreBark : cellBark;
        TrunkCellParts rings = fromCore ? coreRings : cellRings;

        for (Direction face : DIRECTIONS) {
            if (face.getAxis() != Direction.Axis.Y) {
                if (facesTrunk(offset, face)) {
                    continue;
                }
                add(parts, bark.get(cell, face, radius));
                continue;
            }
            if (trunkContinues(level, corePos, face, radius)) {
                continue;
            }
            BranchModelPart part = null;
            // The whole end is capped the same way, so every cell asks about the trunk, not itself.
            // Only an open end asks what grows sideways, which is most of a trunk spared the look.
            if (!(level.getBlockState(corePos.relative(face)).getBlock() instanceof BranchBlock)
                    && !branchesAround(level, corePos)) {
                part = rings.get(cell, face, radius);
            }
            add(parts, part != null ? part : bark.get(cell, face, radius));
        }
    }

    private static void add(List<BlockStateModelPart> parts, @Nullable BranchModelPart part) {
        if (part != null) {
            parts.add(part);
        }
    }

    /** Whether the cell across this face belongs to the same trunk, which spans three by three. */
    private static boolean facesTrunk(Vec3i cell, Direction face) {
        return Math.abs(cell.getX() + face.getStepX()) <= 1
                && Math.abs(cell.getZ() + face.getStepZ()) <= 1;
    }

    /**
     * Whether the trunk carries on past this end, covering it. A narrower slice above or below
     * leaves a rim of this one showing, so it keeps its cap, as it did when the whole trunk was
     * baked from its connection radii.
     */
    private static boolean trunkContinues(BlockAndTintGetter level, BlockPos corePos, Direction face, int radius) {
        BlockState next = level.getBlockState(corePos.relative(face));
        return next.getBlock() instanceof ThickBranchBlock thick && thick.getRadius(next) >= radius;
    }

    /** Whether the cell beside this core holds a shell of this very trunk, which draws it itself. */
    private static boolean hasShell(BlockAndTintGetter level, BlockPos corePos, CoordUtils.Surround cell) {
        BlockPos cellPos = corePos.offset(cell.getOffset());
        BlockState state = level.getBlockState(cellPos);
        return state.getBlock() instanceof TrunkShellBlock
                && cellPos.offset(state.getValue(TrunkShellBlock.CORE_DIR).getOffset()).equals(corePos);
    }

    /** Whether the trunk sprouts a branch sideways here, which takes the rings off its cap. */
    private static boolean branchesAround(BlockAndTintGetter level, BlockPos pos) {
        for (Direction face : Direction.Plane.HORIZONTAL) {
            BlockState neighbour = level.getBlockState(pos.relative(face));
            if (neighbour.getBlock() instanceof BranchBlock && !isTrunk(neighbour)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a block is part of a trunk wider than itself, core or shell. */
    public static boolean isTrunk(BlockState state) {
        if (state.getBlock() instanceof TrunkShellBlock) {
            return true;
        }
        return state.getBlock() instanceof ThickBranchBlock thick
                && thick.getRadius(state) > BranchBlock.MAX_RADIUS;
    }


}