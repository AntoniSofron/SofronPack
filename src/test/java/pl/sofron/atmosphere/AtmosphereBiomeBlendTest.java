package pl.sofron.atmosphere;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AtmosphereBiomeBlendTest {
    @Test
    void weightsSumAndSlideSmoothly() {
        for (double fraction : new double[]{0.0, 0.1, 0.5, 0.9, 0.999}) {
            double sum = 0;
            for (int i = 0; i < 6; i++) {
                double weight = AtmosphereBiomeBlend.axisWeight(fraction, i);
                assertTrue(weight >= 0);
                sum += weight;
            }
            assertEquals(16, sum, 0.0000001);
        }
        assertEquals(4, AtmosphereBiomeBlend.axisWeight(0.0, 1));
        assertEquals(1, AtmosphereBiomeBlend.axisWeight(1.0, 1));
        assertEquals(6, AtmosphereBiomeBlend.axisWeight(0.0, 2));
        assertEquals(4, AtmosphereBiomeBlend.axisWeight(1.0, 2));
    }
}
