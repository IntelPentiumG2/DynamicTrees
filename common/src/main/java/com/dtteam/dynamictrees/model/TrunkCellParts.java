package com.dtteam.dynamictrees.model;

import com.dtteam.dynamictrees.model.parts.BranchModelPart;
import com.dtteam.dynamictrees.utility.CoordUtils;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The faces of a thick trunk, split up by the block each one sits in.
 *
 * <p>A trunk wider than one block spans the branch block at its core and the trunk shells around
 * it. Holding a face per cell lets every one of those blocks draw the part of the trunk inside
 * itself, rather than the core drawing the whole thing into its neighbours: the same surface ends
 * up in the same place, but it belongs to the block it occupies, so it can be culled against that
 * block's neighbours and it survives anything that looks at blocks one at a time.
 *
 * <p>A cell is named by the {@link CoordUtils.Surround} it lies in, or {@code null} for the core.
 */
public class TrunkCellParts {

    private record Key(@Nullable CoordUtils.Surround cell, Direction face, int radius) {}

    private final Map<Key, BranchModelPart> parts = new HashMap<>();

    public void put(@Nullable CoordUtils.Surround cell, Direction face, int radius, @Nullable BranchModelPart part) {
        if (part == null) {
            return;
        }
        parts.put(new Key(cell, face, radius), part);
    }

    @Nullable
    public BranchModelPart get(@Nullable CoordUtils.Surround cell, Direction face, int radius) {
        return parts.get(new Key(cell, face, radius));
    }

    public boolean isEmpty() {
        return parts.isEmpty();
    }

    private static final CoordUtils.Surround[][] CELL_BY_OFFSET = new CoordUtils.Surround[3][3];

    static {
        for (CoordUtils.Surround surround : CoordUtils.Surround.values()) {
            Vec3i off = surround.getOffset();
            CELL_BY_OFFSET[off.getX() + 1][off.getZ() + 1] = surround;
        }
    }

    /** The cell a block sits in relative to the trunk's core, or {@code null} for the core itself. */
    @Nullable
    public static CoordUtils.Surround cellOf(Vec3i offset) {
        if (offset.getY() != 0 || Math.abs(offset.getX()) > 1 || Math.abs(offset.getZ()) > 1) {
            return null;
        }
        return CELL_BY_OFFSET[offset.getX() + 1][offset.getZ() + 1];
    }
}
