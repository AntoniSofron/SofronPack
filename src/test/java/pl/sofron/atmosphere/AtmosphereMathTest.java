package pl.sofron.atmosphere;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AtmosphereMathTest {
    @Test
    void arithmeticAndFunctions() {
        assertEquals(10.25, AtmosphereMath.evaluate("10 + max(-0.25, min(0.25, RAIN * 0.5))", 6000, 0.5, 15), 0.00001);
        assertEquals(0.5, AtmosphereMath.evaluate("1.1 - max(0, min(0.6, 1))", 6000, 0, 15), 0.00001);
    }
    @Test
    void clockAndSky() {
        assertEquals(1, AtmosphereMath.evaluate("(DAY_TIME%24000 > 23000)", 23500, 0, 15));
        assertEquals(0, AtmosphereMath.evaluate("(DAY_TIME%24000 > 23000)", 8000, 0, 15));
        assertEquals(300, AtmosphereMath.evaluate("max(0, 11 - SKY_LIGHT) * 100", 8000, 0, 8));
    }
    @Test
    void rejectsInvalidNames() {
        assertThrows(IllegalArgumentException.class, () -> AtmosphereMath.evaluate("JAVA_CLASS()", 0, 0, 15));
        assertThrows(IllegalArgumentException.class, () -> AtmosphereMath.evaluate("1+", 0, 0, 15));
    }
}
