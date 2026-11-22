package pl.sofron.extended;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedLodFogTest {
    @Test void clearCurveMatchesAllControlPoints() {
        double[] x = {0, 8, 16, 32, 64, 96, 128, 150};
        float[] y = {0f, 0f, .08f, .16f, .32f, .64f, 1f, 1f};
        for (int i = 0; i < x.length; i++)
            assertEquals(y[i], ExtendedLodFog.opacity(x[i]), 1e-5f);
    }

    @Test void stormCurveMatchesAllControlPoints() {
        double[] x = {0, 8, 16, 32, 64, 96, 128, 150};
        float[] y = {0f, .08f, .32f, .64f, .86f, .92f, 1f, 1f};
        for (int i = 0; i < x.length; i++)
            assertEquals(y[i], ExtendedLodFog.opacity(x[i], 1f), 1e-5f);
    }

    @Test void partialWeatherBlendsBothCurves() {
        assertEquals(.04f, ExtendedLodFog.opacity(8, .5f), 1e-5f);
        assertEquals(.40f, ExtendedLodFog.opacity(32, .5f), 1e-5f);
        assertEquals(.59f, ExtendedLodFog.opacity(64, .5f), 1e-5f);
        assertEquals(.16f, ExtendedLodFog.opacity(32, Float.NaN), 1e-5f);
        assertEquals(0f, ExtendedLodFog.opacity(-1, 1f), 1e-5f);
        assertEquals(1f, ExtendedLodFog.opacity(Double.POSITIVE_INFINITY), 1e-5f);
    }

    @Test void bonusesFollowActualCloudDeckHeights() {
        assertEquals(0f, ExtendedLodFog.altitudeBonus(255.9), 1e-5f);
        assertEquals(.08f, ExtendedLodFog.altitudeBonus(256), 1e-5f);
        assertEquals(.08f, ExtendedLodFog.altitudeBonus(383.9), 1e-5f);
        assertEquals(.16f, ExtendedLodFog.altitudeBonus(384), 1e-5f);
        assertEquals(.16f, ExtendedLodFog.altitudeBonus(511.9), 1e-5f);
        assertEquals(.24f, ExtendedLodFog.altitudeBonus(512), 1e-5f);
        assertEquals(.24f, ExtendedLodFog.altitudeBonus(900), 1e-5f);
    }

    @Test void lowYUsesEightPercentPerCommencedFortyBlocks() {
        assertEquals(0f, ExtendedLodFog.altitudeBonus(60), 1e-5f);
        assertEquals(.08f, ExtendedLodFog.altitudeBonus(59), 1e-5f);
        assertEquals(.08f, ExtendedLodFog.altitudeBonus(20), 1e-5f);
        assertEquals(.16f, ExtendedLodFog.altitudeBonus(19), 1e-5f);
        assertEquals(.16f, ExtendedLodFog.altitudeBonus(-20), 1e-5f);
        assertEquals(.24f, ExtendedLodFog.altitudeBonus(-21), 1e-5f);
        assertEquals(.24f, ExtendedLodFog.altitudeBonus(-60), 1e-5f);
        assertEquals(.32f, ExtendedLodFog.altitudeBonus(-61), 1e-5f);
        assertEquals(0f, ExtendedLodFog.altitudeBonus(Double.NaN), 1e-5f);
    }

    @Test void bonusesAddAndClampToOne() {
        assertEquals(.40f, ExtendedLodFog.opacity(64, 0f, 300), 1e-5f);
        assertEquals(.88f, ExtendedLodFog.opacity(32, 1f, 512), 1e-5f);
        assertEquals(1f, ExtendedLodFog.opacity(96, 1f, -64), 1e-5f);
        assertEquals(.24f, ExtendedLodFog.opacity(0, 0f, 512), 1e-5f);
    }

    @Test void regularDistanceFogBeginsAtEightAndEndsAt128() {
        assertEquals(0f, ExtendedLodFog.regularOpacity(-5), 1e-5f);
        assertEquals(0f, ExtendedLodFog.regularOpacity(0), 1e-5f);
        assertEquals(0f, ExtendedLodFog.regularOpacity(8), 1e-5f);
        assertEquals(.5f, ExtendedLodFog.regularOpacity(68), 1e-5f);
        assertEquals(1f, ExtendedLodFog.regularOpacity(128), 1e-5f);
        assertEquals(1f, ExtendedLodFog.regularOpacity(160), 1e-5f);
        assertEquals(1f, ExtendedLodFog.regularOpacity(Double.POSITIVE_INFINITY), 1e-5f);
        assertEquals(0f, ExtendedLodFog.regularOpacity(Double.NaN), 1e-5f);
    }

    @Test void ordinaryFogGrowsGraduallyWithoutBoundaryJumps() {
        assertTrue(ExtendedLodFog.regularOpacity(9) > 0f);
        assertTrue(ExtendedLodFog.regularOpacity(9) < .001f);
        assertTrue(ExtendedLodFog.regularOpacity(16) < .02f);
        float previous = 0f;
        for (int i = 0; i <= 14000; i++) {
            float current = ExtendedLodFog.regularOpacity(i / 100.0);
            assertTrue(current >= previous - 1e-6f);
            previous = current;
        }
        for (double cut : new double[]{8, 128})
            assertEquals(ExtendedLodFog.regularOpacity(cut - .0001),
                         ExtendedLodFog.regularOpacity(cut), 1e-5f);
    }

    @Test void combiningFogLayersPreservesBothAndClampsAtOne() {
        float volume = ExtendedLodFog.opacity(32, 0f, 70);
        float regular = ExtendedLodFog.regularOpacity(32);
        float expected = 1f - (1f - volume) * (1f - regular);
        assertEquals(expected, ExtendedLodFog.combinedOpacity(32, 0f, 70), 1e-5f);
        assertTrue(ExtendedLodFog.combinedOpacity(32, 0f, 70) > volume);
        assertEquals(0f, ExtendedLodFog.combinedOpacity(8, 0f, 70), 1e-5f);
        assertEquals(1f, ExtendedLodFog.combinedOpacity(128, 0f, 70), 1e-5f);
        assertEquals(1f, ExtendedLodFog.combinedOpacity(128, 1f, 512), 1e-5f);
    }

    @Test void sharedShaderUniformsEncodeAndDecodeAltitudeAndWeather() {
        for (double y : new double[]{-64, -20, 20, 59, 60, 255, 256, 384, 512, 900})
            assertEquals(ExtendedLodFog.altitudeBonus(y),
                ExtendedLodFog.altitudeBonusFromEnvironmentalStart(
                    ExtendedLodFog.environmentalStartForAltitude(y)), 1e-5f);
        assertEquals(0f, ExtendedLodFog.strengthFromEnvironmentalEnd(2048), 1e-5f);
        assertEquals(.5f, ExtendedLodFog.strengthFromEnvironmentalEnd(1536), 1e-5f);
        assertEquals(1f, ExtendedLodFog.strengthFromEnvironmentalEnd(1024), 1e-5f);
    }

    @Test void bothCurvesRemainContinuousAndMonotonic() {
        for (float weather : new float[]{0f, .25f, .5f, 1f}) {
            float prev = -1f;
            for (int i = 0; i <= 13000; i++) {
                float opacity = ExtendedLodFog.opacity(i / 100d, weather);
                assertTrue(opacity >= prev - 1e-5f);
                assertTrue(opacity >= 0f && opacity <= 1f);
                prev = opacity;
            }
            for (double edge : new double[]{8,16,32,64,96,128})
                assertEquals(ExtendedLodFog.opacity(edge - .0001, weather),
                    ExtendedLodFog.opacity(edge, weather), 5e-5f);
        }
    }
}
