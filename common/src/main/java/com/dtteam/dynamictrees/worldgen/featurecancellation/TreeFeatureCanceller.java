package com.dtteam.dynamictrees.worldgen.featurecancellation;

import com.dtteam.dynamictrees.api.worldgen.BiomePropertySelectors;
import com.dtteam.dynamictrees.api.worldgen.FeatureCanceller;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.WeightedPlacedFeature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

public class TreeFeatureCanceller<T extends FeatureConfiguration> extends FeatureCanceller {

    private final Class<T> treeFeatureConfigClass;

    public TreeFeatureCanceller(final Identifier registryName, Class<T> treeFeatureConfigClass) {
        super(registryName);
        this.treeFeatureConfigClass = treeFeatureConfigClass;
    }

    @Override
    public boolean shouldCancel(ConfiguredFeature<?, ?> configuredFeature, BiomePropertySelectors.NormalFeatureCancellation featureCancellations) {

        /*  The following code removes vanilla trees from the biome's generator.
            There may be some problems as MultipleRandomFeatures can store other features too,
            so these are currently removed from world gen too. The list is immutable so they can't be removed individually,
            but one (unclean) solution may be to add the non-tree features back to the generator. */

        if (isCancellableTree(configuredFeature, featureCancellations)) {
            return true;
        }

        final FeatureConfiguration featureConfig = configuredFeature.config();
        if (featureConfig instanceof RandomFeatureConfiguration randomFeatureConfig) {
            // Removes configuredFeature if it contains trees.
            return this.doesContainTrees(randomFeatureConfig, featureCancellations);
        }

        // Trees are commonly reached through some other wrapper, so follow whatever this one nests.
        return configuredFeature.getSubFeatures()
                .map(Holder::value)
                .anyMatch(subFeature -> isCancellableTree(subFeature, featureCancellations));
    }

    /**
     * Removes any individual tree whose namespace matches.
     */
    protected boolean isCancellableTree(ConfiguredFeature<?, ?> configuredFeature, BiomePropertySelectors.NormalFeatureCancellation featureCancellations) {
        if (!this.treeFeatureConfigClass.isInstance(configuredFeature.config())) {
            return false;
        }
        final Identifier featureRegistryName = BuiltInRegistries.FEATURE.getKey(configuredFeature.feature());
        return featureRegistryName != null && featureCancellations.shouldCancelNamespace(featureRegistryName.getNamespace());
    }

    private boolean doesContainTrees(RandomFeatureConfiguration featureConfig, BiomePropertySelectors.NormalFeatureCancellation featureCancellations) {
        for (WeightedPlacedFeature feature : featureConfig.features) {
            if (containsTree(feature.feature.value(), featureCancellations)) {
                return true;
            }
        }
        return containsTree(featureConfig.defaultFeature.value(), featureCancellations);
    }

    private boolean containsTree(PlacedFeature placedFeature, BiomePropertySelectors.NormalFeatureCancellation featureCancellations) {
        return placedFeature.getFeatures()
                .map(Holder::value)
                .anyMatch(configuredFeature -> isCancellableTree(configuredFeature, featureCancellations));
    }

}