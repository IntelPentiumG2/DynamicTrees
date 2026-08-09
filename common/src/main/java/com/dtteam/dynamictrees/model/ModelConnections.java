package com.dtteam.dynamictrees.model;

import com.dtteam.dynamictrees.api.network.Connections;
import com.dtteam.dynamictrees.block.branch.BranchBlock;
import com.dtteam.dynamictrees.tree.family.Family;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * Extension of {@link Connections} for storing and transferring model data to baked models.
 */
public class ModelConnections extends Connections {

    private Direction ringOnly = null;
    private Family family = Family.NULL_FAMILY;

    public ModelConnections() {}

    public ModelConnections(Connections connections) {
        this.setAllRadii(connections.getAllRadii());
    }

    public ModelConnections(int[] radii) {
        super(radii);
    }

    public ModelConnections(Direction ringDir) {
        ringOnly = ringDir;
    }

    public ModelConnections setAllRadii(int[] radii) {
        return (ModelConnections) super.setAllRadii(radii);
    }

    public ModelConnections setFamily(Family family) {
        this.family = family;
        return this;
    }

    public ModelConnections setFamily(@Nullable BranchBlock branch) {
        if (branch != null) {
            this.family = branch.getFamily();
        }
        return this;
    }

    public Family getFamily() {
        return family;
    }

    public Direction getRingOnly() {
        return ringOnly;
    }

    public void setForceRing(Direction ringSide) {
        ringOnly = ringSide;
    }

    @Override
    public boolean equals(Object obj) {
        if (!super.equals(obj)) return false;
        final ModelConnections other = (ModelConnections) obj;
        return ringOnly == other.ringOnly && family == other.family;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (ringOnly == null ? 0 : ringOnly.hashCode());
        result = 31 * result + System.identityHashCode(family);
        return result;
    }

//    public static final ModelProperty<ModelConnections> CONNECTIONS_PROPERTY = new ModelProperty<>();
//
//    public ModelData toModelData() {
//        return ModelData.builder().with(CONNECTIONS_PROPERTY, this).build();
//    }
//
//    public ModelData toModelData(ModelData baseData) {
//        return baseData.derive().with(CONNECTIONS_PROPERTY, this).build();
//    }

}