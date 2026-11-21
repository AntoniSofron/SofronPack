package pl.sofron.atmosphere;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class CloudBandLayoutTest {
    @Test void exactlyThreeCloudLayersUseRequestedHeights() {
        assertEquals(3, CloudBandLayout.BAND_COUNT);
        assertArrayEquals(new float[]{256f,384f,512f}, CloudBandLayout.heights());
    }

    @Test void cloudLayersAlwaysDrawBackToFront() {
        for (float y : new float[]{-64f,256f,320f,384f,460f,512f,700f}) {
            var order = CloudBandLayout.farToNear(y);
            assertEquals(3, Arrays.stream(order).distinct().count());
            float[] levels = CloudBandLayout.heights();
            for (int i = 0; i < order.length - 1; i++) {
                assertTrue(Math.abs(y-levels[order[i]]) >=
                        Math.abs(y-levels[order[i+1]]));
            }
        }
    }
}
