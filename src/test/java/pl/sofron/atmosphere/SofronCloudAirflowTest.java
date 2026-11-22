package pl.sofron.atmosphere;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SofronCloudAirflowTest {
    @Test void speedBoostsAtAllThreeCloudHeights() {
        assertEquals(1.0, SofronCloudAirflow.altitudeFactor(255.99));
        assertEquals(1.25, SofronCloudAirflow.altitudeFactor(256));
        assertEquals(1.25, SofronCloudAirflow.altitudeFactor(383.99));
        assertEquals(1.50, SofronCloudAirflow.altitudeFactor(384));
        assertEquals(1.50, SofronCloudAirflow.altitudeFactor(511.99));
        assertEquals(1.75, SofronCloudAirflow.altitudeFactor(512));
        assertEquals(1.75, SofronCloudAirflow.altitudeFactor(800));
    }

    @Test void cloudHalvesPoweredFlightButOnlyReducesFallingByThirtyPercent() {
        Vec3 forward = new Vec3(2, 0, -4);
        var outside = SofronCloudAirflow.transform(forward, 450, true, false, false);
        assertEquals(new Vec3(3, 0, -6), outside);
        var inside = SofronCloudAirflow.transform(forward, 450, true, false, true);
        assertEquals(new Vec3(1.5, 0, -3), inside);
        var falling = SofronCloudAirflow.transform(new Vec3(0.25, -2, 0.5),
                400, false, true, true);
        assertEquals(-1.4, falling.y, 1e-10);
        assertEquals(0.25, falling.x);
        assertEquals(0.5, falling.z);
        assertEquals(new Vec3(0.25, -2, 0.5),
                SofronCloudAirflow.transform(new Vec3(0.25, -2, 0.5),
                        400, false, true, false));
    }

    @Test void cloudWindUsesTheSameTwelveBlockGridAndThreeOffsetsAsRendering() {
        assertEquals(0, SofronCloudAirflow.cloudColumn(0, 0, 0));
        assertEquals(-1, SofronCloudAirflow.cloudColumn(-0.001, 0, 0));
        assertEquals(23, SofronCloudAirflow.cloudColumn(0, 0, 1));
        assertEquals(47, SofronCloudAirflow.cloudColumn(0, 0, 2));
        assertEquals(1, SofronCloudAirflow.cloudColumn(0, 400, 0));
        assertEquals(0, SofronCloudAirflow.cloudRow(0));
        assertEquals(1, SofronCloudAirflow.cloudRow(9));
        assertEquals(-1, SofronCloudAirflow.cloudRow(-4));
    }

    @Test void noOrdinaryWalkOrNoncloudFallIsModified() {
        Vec3 original = new Vec3(0.2, -0.08, 0.1);
        assertSame(original, SofronCloudAirflow.transform(original, 600, false, false, true));
        assertSame(original, SofronCloudAirflow.transform(original, 600, false, true, false));
    }
}
