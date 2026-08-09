package com.dtteam.dynamictrees.systems.genfeature.context;

import com.dtteam.dynamictrees.api.network.MapSignal;
import com.dtteam.dynamictrees.block.soil.SoilBlock;
import com.dtteam.dynamictrees.systems.nodemapper.FindEndsNode;
import com.dtteam.dynamictrees.tree.TreeHelper;
import com.dtteam.dynamictrees.tree.species.Species;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * @author Harley O'Connor
 */
public class PostGrowContext extends GenFeatureContext {

    private final BlockPos treePos;
    private final int fertility;
    private final boolean natural;

    /**
     * Instantiates a new {@link PostGrowContext} object.
     *
     * @param rootPos   The {@link BlockPos} of the {@link SoilBlock} the generated tree is planted on.
     * @param treePos   The {@link BlockPos} of the base trunk block of the tree (usually directly above the rooty dirt
     *                  block).
     * @param species   The {@link Species} being grown.
     * @param fertility The fertility of the {@link SoilBlock} the tree is planted in.
     * @param natural   If {@code true}, this member is being used to grow the tree naturally (create drops or fruit),
     *                  otherwise this member is being used to grow a tree with a growth accelerant like bonemeal or the
     *                  potion of burgeoning.
     */
    public PostGrowContext(Level level, BlockPos rootPos, Species species, BlockPos treePos, int fertility, boolean natural) {
        super(level, rootPos, species);
        this.treePos = treePos;
        this.fertility = fertility;
        this.natural = natural;
    }

    public BlockPos treePos() {
        return treePos;
    }

    public int fertility() {
        return fertility;
    }

    public boolean natural() {
        return natural;
    }

    private List<BlockPos> memoizedEndPoints;

    /**
     * The branch endpoints of the grown tree. The network is analysed at most once per grow event,
     * on first call, and the result is shared by every gen feature handling that event.
     */
    public List<BlockPos> endPoints() {
        if (memoizedEndPoints == null) {
            final FindEndsNode endFinder = new FindEndsNode();
            TreeHelper.startAnalysisFromRoot(level(), pos(), new MapSignal(endFinder));
            memoizedEndPoints = endFinder.getEnds();
        }
        return memoizedEndPoints;
    }

}
