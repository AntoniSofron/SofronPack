package pl.sofron.optimization;

import org.junit.jupiter.api.Test;
import pl.sofron.optimization.scheduler.SofronChunkWorkBudget;

import static org.junit.jupiter.api.Assertions.*;

class SofronChunkWorkBudgetTest {
    @Test void startsAtBoundedSafeValue() {
        SofronChunkWorkBudget model = new SofronChunkWorkBudget(200_000);
        assertEquals(8, model.budget(8, 2_000_000));
        assertEquals(2, model.budget(8, 400_000));
        assertThrows(IllegalArgumentException.class, () -> model.budget(0, 1));
        assertThrows(IllegalArgumentException.class, () -> model.budget(1, 0));
    }

    @Test void adaptsToSlowSnapshotCopiesAndRecoversAfterFastCopies() {
        SofronChunkWorkBudget model = new SofronChunkWorkBudget(200_000);
        for (int i = 0; i < 12; i++) {
            model.sample(8_000_000);
            model.budget(8, 2_000_000);
        }
        assertEquals(1, model.budget(8, 2_000_000));
        for (int i = 0; i < 28; i++) {
            model.sample(50_000);
            model.budget(8, 2_000_000);
        }
        assertEquals(8, model.budget(8, 2_000_000));
    }

    @Test void clampsBadSampleAndNeverRequestsMoreThanTheExistingLimit() {
        SofronChunkWorkBudget model = new SofronChunkWorkBudget(30_000);
        model.sample(-100);
        assertEquals(4, model.budget(4, 5_000_000));
        model.sample(Long.MAX_VALUE);
        assertTrue(model.budget(4, 1_000_000) >= 1);
        assertThrows(IllegalArgumentException.class, () -> new SofronChunkWorkBudget(Double.NaN));
    }
}
