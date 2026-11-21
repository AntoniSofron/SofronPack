package pl.sofron.atmosphere;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;

final class AtmosphereBiomeBlend {
    private static final SurfaceBiomeCache BIOMES = new SurfaceBiomeCache(1024, 200);
    private static final double[] KERNEL = {0.0, 1.0, 4.0, 6.0, 4.0, 1.0, 0.0};
    private static final String[] window = new String[36];
    private static int windowX, windowZ;
    private static AtmosphereBiomeBlend uniform;
    private final Map<String, Double> weights;

    private AtmosphereBiomeBlend(Map<String, Double> weights) {
        this.weights = Map.copyOf(weights);
    }

    Map<String, Double> weights() {
        return weights;
    }

    static double axisWeight(double fraction, int sample) {
        return KERNEL[sample + 1] + fraction * (KERNEL[sample] - KERNEL[sample + 1]);
    }

    static AtmosphereBiomeBlend sampleSurface(ClientLevel level, double x, double z) {
        double sampleX = x * 0.25 - 0.5;
        double sampleZ = z * 0.25 - 0.5;
        int integralX = (int)Math.floor(sampleX);
        int integralZ = (int)Math.floor(sampleZ);
        double fractionX = sampleX - integralX;
        double fractionZ = sampleZ - integralZ;
        // Cache noise biome lookups at quart-column coordinates. A single camera
        // movement previously requested the same 36 heightmap/biome lookups
        // again even if the queried columns had not changed.
        boolean expired = BIOMES.prepare(level, level.getGameTime());
        if (expired || integralX != windowX || integralZ != windowZ) {
            SurfaceBiomeCache.Resolver resolver = (qx, qz) -> {
                int bx = qx * 4 + 2, bz = qz * 4 + 2;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
                return level.getBiomeManager().getNoiseBiomeAtPosition(bx, y, bz)
                        .unwrapKey().map(key -> key.identifier().toString()).orElse("");
            };
            boolean same = true;
            for (int zi = 0; zi < 6; zi++) for (int xi = 0; xi < 6; xi++) {
                int i = zi * 6 + xi;
                window[i] = BIOMES.get(integralX - 2 + xi, integralZ - 2 + zi, resolver);
                same &= window[i].equals(window[0]);
            }
            uniform = same ? new AtmosphereBiomeBlend(Map.of(window[0], 1.0)) : null;
            windowX = integralX;
            windowZ = integralZ;
        }
        if (uniform != null) return uniform;
        Map<String, Double> totals = new HashMap<>();
        double weightSum = 0;

        for (int zi = 0; zi < 6; zi++) {
            double wz = axisWeight(fractionZ, zi);
            if (wz <= 0) continue;
            for (int xi = 0; xi < 6; xi++) {
                double wx = axisWeight(fractionX, xi);
                if (wx <= 0) continue;
                double weight = wx * wz;
                String id = window[zi * 6 + xi];
                totals.merge(id, weight, Double::sum);
                weightSum += weight;
            }
        }
        if (weightSum <= 0) return new AtmosphereBiomeBlend(Map.of());
        double sum = weightSum;
        totals.replaceAll((id, weight) -> weight / sum);
        return new AtmosphereBiomeBlend(totals);
    }

    Vector3fc blendColors(Vector3fc base, Function<String, Vector3fc> lookup) {
        float red = 0, green = 0, blue = 0;
        for (var entry : weights.entrySet()) {
            Vector3fc color = lookup.apply(entry.getKey());
            if (color == null) color = base;
            float weight = entry.getValue().floatValue();
            red += color.x() * weight;
            green += color.y() * weight;
            blue += color.z() * weight;
        }
        return weights.isEmpty() ? base : new Vector3f(red, green, blue);
    }
}
