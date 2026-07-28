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
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record ThickBranchBlockStateModel(
        BranchBlockStateModel fallback,
        BranchMultiPartHolder trunkBark,
        BranchMultiPartHolder trunkRings,
        TrunkCellParts cellBark,
        TrunkCellParts cellRings
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
        return cellKey(level, pos, coreRadius, null);
    }

    /** What a cell's geometry depends on: its radius, which cell it is, and what surrounds it. */
    public static Long cellKey(BlockAndTintGetter level, BlockPos pos, int radius,
                               @Nullable CoordUtils.Surround cell) {
        long key = radius | (long) (cell == null ? CoordUtils.Surround.values().length : cell.ordinal()) << 8;
        if (branchesAround(level, pos)) {
            key |= 1L << 16;
        }
        for (Direction face : Direction.values()) {
            BlockState neighbour = level.getBlockState(pos.relative(face));
            if (isTrunk(neighbour)) {
                key |= 1L << (20 + face.ordinal());
            } else if (neighbour.getBlock() instanceof BranchBlock) {
                key |= 1L << (32 + face.ordinal());
            }
        }
        return key;
    }

    @Override
    public void collectParts(BlockState state, List<BlockStateModelPart> parts, Connections connectionsData) {
        int coreRadius = TreeHelper.getRadius(state);
        if (coreRadius <= BranchBlock.MAX_RADIUS) {
            fallback.collectParts(state, parts, connectionsData);
            return;
        }
        coreRadius = Mth.clamp(coreRadius, BranchBlock.MAX_RADIUS + 1, ThickBranchBlock.MAX_RADIUS_THICK);

        final int[] connections = connectionsData.getAllRadii();
        final Direction forceRingDir = ((ModelConnections) connectionsData).getRingOnly();
        final int twigRadius = ((ModelConnections) connectionsData).getFamily().getPrimaryThickness();

        int numConnections = BranchBlockStateModel.countConnections(connections);

        if (numConnections == 0 && forceRingDir != null) return;

        if (forceRingDir != null) {
            connections[forceRingDir.get3DDataValue()] = 0;
            parts.add(trunkRings.getPart(forceRingDir, coreRadius));
        }

        boolean branchesAround = areBranchesAround(connections);

        for (Direction face : Direction.values()) {
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
        collectCell(level, pos, Mth.clamp(coreRadius, BranchBlock.MAX_RADIUS + 1, ThickBranchBlock.MAX_RADIUS_THICK),
                null, parts);
    }

    /**
     * Draws the slice of trunk standing in one block, with the faces its neighbours leave exposed.
     *
     * <p>Called by the branch block at the trunk's core for its own cell, and by each trunk shell
     * around it for theirs. A face shared with another part of the trunk is dropped rather than
     * drawn and hidden, which is why this needs the world; the two ends are capped with rings
     * unless a branch grows out of them, matching what the trunk did as one piece.
     */
    public void collectCell(BlockAndTintGetter level, BlockPos pos, int radius,
                            @Nullable CoordUtils.Surround cell, List<BlockStateModelPart> parts) {
        for (Direction face : Direction.values()) {
            BlockState neighbour = level.getBlockState(pos.relative(face));
            if (isTrunk(neighbour)) {
                continue; // The neighbouring block is trunk too; this face is inside it.
            }
            BranchModelPart part = null;
            // Only an open end asks what grows sideways, which is most of a trunk spared the look.
            if (face.getAxis() == Direction.Axis.Y
                    && !(neighbour.getBlock() instanceof BranchBlock)
                    && !branchesAround(level, pos)) {
                part = cellRings.get(cell, face, radius);
            }
            if (part == null) {
                part = cellBark.get(cell, face, radius);
            }
            if (part != null) {
                parts.add(part);
            }
        }
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