package com.dtteam.dynamictrees.model.blockstate;

import com.dtteam.dynamictrees.block.branch.BranchBlock;
import com.dtteam.dynamictrees.block.branch.ThickBranchBlock;
import com.dtteam.dynamictrees.block.branch.TrunkShellBlock;
import com.dtteam.dynamictrees.model.TrunkCellParts;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;
import net.neoforged.neoforge.client.model.block.CustomUnbakedBlockStateModel;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Draws the slice of trunk standing in a trunk shell.
 *
 * <p>A trunk wider than one block is a branch block ringed by shells. The branch knows the tree's
 * family, its radius and its textures; the shell knows only which way the branch lies. So the shell
 * asks the branch: it resolves its muse, picks up the model already baked for that branch, and asks
 * that model for the cell it occupies. Nothing is baked twice, and the bark is the muse's own.
 *
 * <p>With no muse to find — a shell asked about outside a world — there is nothing to draw, which
 * is what a shell rendered to before it drew anything at all.
 */
public record TrunkShellBlockStateModel(Material.Baked particleMaterial) implements DynamicBlockStateModel {

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
                             List<BlockStateModelPart> parts) {
        Trunk trunk = trunkOf(level, pos, state);
        if (trunk != null) {
            trunk.model().collectCell(level, pos, trunk.radius(), trunk.cell(), parts);
        }
    }

    @Override
    public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        Trunk trunk = trunkOf(level, pos, state);
        if (trunk == null) {
            return TrunkShellBlockStateModel.class;
        }
        return ThickBranchBlockStateModel.cellKey(level, pos, trunk.radius(), trunk.cell());
    }

    @Override
    public @BakedQuad.MaterialFlags int materialFlags() {
        return 0;
    }

    // The shell's quads are the muse's, so its render layers and its particles are too.

    @Override
    public @BakedQuad.MaterialFlags int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        Trunk trunk = trunkOf(level, pos, state);
        return trunk == null ? 0 : trunk.model().materialFlags();
    }

    @Override
    public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        Trunk trunk = trunkOf(level, pos, state);
        return trunk == null ? particleMaterial : trunk.model().particleMaterial();
    }

    /** The trunk a shell belongs to: which model draws it, how wide it is, and which cell this is. */
    private record Trunk(ThickBranchBlockStateModel model, int radius, @Nullable com.dtteam.dynamictrees.utility.CoordUtils.Surround cell) {}

    @Nullable
    private static Trunk trunkOf(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof TrunkShellBlock shell)) {
            return null;
        }
        TrunkShellBlock.ShellMuse muse = shell.getMuseUnchecked(level, state, pos);
        if (muse == null || !(muse.state().getBlock() instanceof ThickBranchBlock branch)) {
            return null;
        }
        int radius = branch.getRadius(muse.state());
        if (radius <= BranchBlock.MAX_RADIUS) {
            return null; // Not wide enough to reach out of its own block.
        }
        BlockStateModel museModel = Minecraft.getInstance().getModelManager()
                .getBlockStateModelSet().get(muse.state());
        if (!(museModel instanceof ThickBranchBlockStateModel thick)) {
            return null;
        }
        Vec3i offset = pos.subtract(muse.pos());
        var cell = TrunkCellParts.cellOf(offset);
        return cell == null ? null : new Trunk(thick, Math.min(radius, ThickBranchBlock.MAX_RADIUS_THICK), cell);
    }

    public record Unbaked(Identifier particleTexture) implements CustomUnbakedBlockStateModel {

        public static final String PARTICLE_TEXTURE = "particle";

        public static final MapCodec<Unbaked> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Identifier.CODEC.fieldOf(PARTICLE_TEXTURE).forGetter(Unbaked::particleTexture)
        ).apply(i, Unbaked::new));

        @Override
        public MapCodec<? extends CustomUnbakedBlockStateModel> codec() {
            return CODEC;
        }

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {}

        @Override
        public BlockStateModel bake(ModelBaker baker) {
            return new TrunkShellBlockStateModel(
                    baker.materials().get(new Material(particleTexture), particleTexture::toDebugFileName));
        }
    }
}
