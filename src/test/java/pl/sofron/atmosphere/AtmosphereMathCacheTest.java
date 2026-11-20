package pl.sofron.atmosphere;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AtmosphereMathCacheTest {
    @Test
    void compiledExpressionsAreReusableAcrossEnvironmentalChanges() {
        String input = "0.8 - max(0, min(0.35, (DAY_TIME%24000 > 23000) + (RAIN * 0.35) - max(0, 11 - SKY_LIGHT)))";
        AtmosphereMath.clearCache();
        for (int t=0;t<4000;t++) {
            double clear=AtmosphereMath.evaluate(input,6000,0,15);
            double storm=AtmosphereMath.evaluate(input,6000,1,15);
            assertEquals(0.8,clear,0.000001);
            assertEquals(0.45,storm,0.000001);
        }
    }
    @Test
    void preservesOperatorsAndPrecedence() {
        assertEquals(1,AtmosphereMath.evaluate("8/2 + 2*3 == 10",12,0,15));
        assertEquals(1,AtmosphereMath.evaluate("DAY_TIME%24000 > 23000",23500,0,15));
        assertEquals(1,AtmosphereMath.evaluate("(RAIN + 2) >= 3",0,1,15));
        assertThrows(IllegalArgumentException.class,()->AtmosphereMath.evaluate("1 +",0,0,0));
        assertThrows(IllegalArgumentException.class,()->AtmosphereMath.evaluate("bad_variable",0,0,0));
    }
}
