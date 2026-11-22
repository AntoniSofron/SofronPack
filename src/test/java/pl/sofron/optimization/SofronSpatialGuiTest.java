package pl.sofron.optimization;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import pl.sofron.optimization.gui.SofronScreenRectangleQuadTree;

/** Differential tests against vanilla-style exact rectangle intersection. */
class SofronSpatialGuiTest {
    private static ScreenRectangle rect(int x, int y, int w, int h) {
        return new ScreenRectangle(new ScreenPosition(x, y), w, h);
    }

    @Test void handlesNegativePositionsManySplitsAndRootGrowth() {
        Random rand = new Random(3871825L);
        SofronScreenRectangleQuadTree tree = new SofronScreenRectangleQuadTree();
        List<ScreenRectangle> baseline = new ArrayList<>();
        for (int n = 0; n < 2000; n++) {
            ScreenRectangle area = rect(rand.nextInt(100000) - 50000,
                    rand.nextInt(80000) - 40000, rand.nextInt(600) + 1, rand.nextInt(600) + 1);
            tree.add(area);
            baseline.add(area);
        }
        for (int n = 0; n < 3500; n++) {
            ScreenRectangle probe = rect(rand.nextInt(100000) - 50000,
                    rand.nextInt(80000) - 40000, rand.nextInt(700) + 1, rand.nextInt(700) + 1);
            boolean expected = baseline.stream().anyMatch(area -> area.intersects(probe));
            assertEquals(expected, tree.intersects(probe), "Mismatch on rectangle " + n);
        }
    }

    @Test void emptyTreeAndDisjointAndTouchingRectangles() {
        SofronScreenRectangleQuadTree tree = new SofronScreenRectangleQuadTree();
        assertFalse(tree.intersects(rect(0, 0, 4, 4)));
        tree.add(rect(0, 0, 10, 10));
        assertFalse(tree.intersects(rect(10, 10, 10, 10)));
        assertTrue(tree.intersects(rect(9, 9, 1, 1)));
    }
}
