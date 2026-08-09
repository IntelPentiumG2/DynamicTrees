package com.dtteam.dynamictrees.worldgen;

import com.dtteam.dynamictrees.api.worldgen.RadiusCoordinator;
import com.dtteam.dynamictrees.deserialization.math.MathContext;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;

import java.util.function.Function;

public class BiomeRadiusCoordinator implements RadiusCoordinator {
    
    protected final LevelAccessor level;
    protected final Identifier dimensionName;
    protected int pass;
    protected Function<Integer, Integer> chunkMultipass;

    public BiomeRadiusCoordinator(Identifier dimensionName, LevelAccessor level) {
        this.level = level;
        this.dimensionName = dimensionName;
    }

    @Override
    public int getRadiusAtCoords(int x, int z) {
        int rad = this.chunkMultipass.apply(pass);
        if (rad >= 2 && rad <= 8) {
            return rad;
        }
        
        final double density = calcDensity(x, z);
        return calcRadius(x, z, density);
    }
    
    // One-entry memo: the disc solver asks about tightly clustered coordinates, which usually share
    // a biome quart cell — this avoids re-running the multi-noise climate sampler for each query.
    private long lastQuartKey = Long.MIN_VALUE;
    private BiomePropertySelectorsHolder lastSelector;

    private record BiomePropertySelectorsHolder(com.dtteam.dynamictrees.api.worldgen.BiomePropertySelectors.DensitySelector selector) {}

    private double calcDensity(int x, int z) {
        final int qx = (x + 8) >> 2;
        final int qz = (z + 8) >> 2;
        final long key = ((long) qx << 32) ^ (qz & 0xFFFFFFFFL);
        if (key != lastQuartKey || lastSelector == null) {
            final Holder<Biome> biome = this.level.getUncachedNoiseBiome(qx, level.getMaxY() >> 2, qz); // Placement is offset by +8,+8
            lastSelector = new BiomePropertySelectorsHolder(BiomeDatabases
                    .getDimensionalOrDefault(dimensionName)
                    .getDensitySelector(biome));
            lastQuartKey = key;
        }
        final Vec3i pos = new Vec3i(x, 0, z);
        final RandomSource randomSource = this.level.getRandom();
        final MathContext mathContext = new MathContext(pos, randomSource);
        return lastSelector.selector().getDensity(mathContext);
    }
    
    private int calcRadius(int x, int z, double density) {
        final double size = ((1.0 - density) * 9); // Size is the inverse of density (gives 0 to 9)
        
        // Oh, Joy. RandomSource can potentially start with the same number for each chunk. Let's just
        // throw this large prime xor hack in there to get it to at least look like it's random.
        int kindaRandom = ((x * 674365771) ^ (z * 254326997)) >> 4;
        int shakelow = (kindaRandom & 0x3) % 3; // Produces 0,0,1 or 2
        int shakehigh = (kindaRandom & 0xc) % 3; // Produces 0,0,1 or 2
        
        return Mth.clamp((int) size, 2 + shakelow, 8 - shakehigh); // Clamp to tree volume radius range
    }
    
    @Override
    public boolean runPass(int chunkX, int chunkZ, int pass) {
        this.pass = pass;

        if (pass == 0) {
            final Holder<Biome> biome = this.level.getUncachedNoiseBiome(((chunkX << 4) + 8) >> 2, level.getMaxY() >> 2, ((chunkZ << 4) + 8) >> 2); // Aim at center of chunk
            this.chunkMultipass = BiomeDatabases.getDimensionalOrDefault(this.dimensionName).getMultipass(biome);
        }

        return this.chunkMultipass.apply(pass) >= 0;
    }

}
