package com.dtteam.dynamictrees.systems;

import com.dtteam.dynamictrees.block.FutureBreakable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class FutureBreak {

    public static final List<FutureBreak> FUTURE_BREAKS = new ArrayList<>();

    public final BlockState state;
    public final Level level;
    public final BlockPos pos;
    public final LivingEntity entity;
    public int ticks;

    public FutureBreak(BlockState state, Level level, BlockPos pos, LivingEntity entity, int ticks) {
        this.state = state;
        this.level = level;
        this.pos = pos;
        this.entity = entity;
        this.ticks = ticks;
    }

    public static void add(FutureBreak fb) {
        if (!fb.level.isClientSide()) {
            FUTURE_BREAKS.add(fb);
        }
    }

    public static void process(Level level) {
        if (FUTURE_BREAKS.isEmpty()) {
            return;
        }

        // Iterate a snapshot since futureBreak() may add new entries; remove by index to avoid O(n) scans.
        final FutureBreak[] snapshot = FUTURE_BREAKS.toArray(new FutureBreak[0]);
        for (final FutureBreak futureBreak : snapshot) {
            if (level != futureBreak.level) {
                continue;
            }

            if (!(futureBreak.state.getBlock() instanceof FutureBreakable futureBreakable)) {
                removeIdentity(futureBreak);
                continue;
            }

            if (futureBreak.ticks-- > 0) {
                continue;
            }

            futureBreakable.futureBreak(futureBreak.state, level, futureBreak.pos, futureBreak.entity);
            removeIdentity(futureBreak);
        }
    }

    private static void removeIdentity(FutureBreak futureBreak) {
        for (final Iterator<FutureBreak> it = FUTURE_BREAKS.iterator(); it.hasNext(); ) {
            if (it.next() == futureBreak) {
                it.remove();
                return;
            }
        }
    }

}

