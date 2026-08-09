package com.dtteam.dynamictrees.model.entity;

import com.dtteam.dynamictrees.entity.FallingTreeEntity;
import com.dtteam.dynamictrees.platform.ClientServices;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class FallingTreeEntityModelTrackerCache {

    private static ConcurrentMap<Integer, FallingTreeEntityModel> models = new ConcurrentHashMap<>();

    @Nullable
    public static FallingTreeEntityModel getOrCreateModel(FallingTreeEntity entity) {
        if (entity.level().isClientSide())
            return models.computeIfAbsent(entity.getId(), i -> ClientServices.CLIENT.newFallingTreeEntityModel(entity));
        return null;
    }

    public static void cleanupModels(Level level, FallingTreeEntity entity) {
        if (level.isClientSide()){
            models.remove(entity.getId());
        }
    }

    public static void cleanupModels(Level level) {
        // Sweep in place; entries for dead entities are also removed eagerly in cleanupModels(level, entity).
        models.keySet().removeIf(id -> level.getEntity(id) == null);
    }
}
