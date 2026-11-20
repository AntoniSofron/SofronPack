package pl.sofron.atmosphere;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AtmosphereFogMathTest {
    @Test
    void stormsCanThickenFogInDryBiomes() {
        assertEquals(0.0, AtmosphereFogMath.stormStrength(0.0, 0.0));
        assertEquals(0.85, AtmosphereFogMath.stormStrength(0.0, 0.85), 0.00001);
        assertEquals(1.0, AtmosphereFogMath.stormStrength(0.4, 1.0));
        String desert = "0.8 - max(0, min(0.35, (DAY_TIME%24000 > 23000) + "
                + "(DAY_TIME%24000 < 1000) + (RAIN * 0.35) - max(0, 11 - SKY_LIGHT)))";
        double clear = AtmosphereMath.evaluate(desert, 6000, 0.0, 15);
        double storm = AtmosphereMath.evaluate(desert, 6000, 1.0, 15);
        assertEquals(0.8, clear, 0.000001);
        assertEquals(0.45, storm, 0.000001);
        assertTrue(storm < clear);
    }

    @Test
    void caveFogRequiresEnclosureAndDepth() {
        assertEquals(0, AtmosphereFogMath.caveWeight(0, 0));
        assertEquals(0, AtmosphereFogMath.caveWeight(40, 15));
        assertEquals(1, AtmosphereFogMath.caveWeight(40, 0));
        double shallow = AtmosphereFogMath.caveWeight(10, 0);
        double deep = AtmosphereFogMath.caveWeight(18, 0);
        assertTrue(shallow > 0 && shallow < deep && deep < 1);
        assertTrue(AtmosphereFogMath.caveWeight(20, 8) <
                AtmosphereFogMath.caveWeight(20, 0));
    }

    @Test
    void blendingIsContinuous() {
        assertEquals(100.0, AtmosphereFogMath.interpolate(100, 50, 0));
        assertEquals(50.0, AtmosphereFogMath.interpolate(100, 50, 1));
        assertEquals(75.0, AtmosphereFogMath.interpolate(100, 50, 0.5));
        assertEquals(75.0, AtmosphereFogMath.interpolate(100, 50, 0.500001), 0.0001);
    }
}
