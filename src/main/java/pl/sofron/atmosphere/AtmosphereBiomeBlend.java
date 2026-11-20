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
    private static final double[] KERNEL = {0.0, 1.0, 4.0, 6.0, 4.0, 1.0, 0.0};
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
        Map<String, Double> totals = new HashMap<>();
        double weightSum = 0;

        for (int zi = 0; zi < 6; zi++) {
            double wz = axisWeight(fractionZ, zi);
            if (wz <= 0) continue;
            int blockZ = 4 * (integralZ - 2 + zi) + 2;
            for (int xi = 0; xi < 6; xi++) {
                double wx = axisWeight(fractionX, xi);
                if (wx <= 0) continue;
                int blockX = 4 * (integralX - 2 + xi) + 2;
                double weight = wx * wz;
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
                var biome = level.getBiomeManager().getNoiseBiomeAtPosition(
                        blockX, surfaceY, blockZ);
                String id = biome.unwrapKey()
                        .map(key -> key.identifier().toString()).orElse("");
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
