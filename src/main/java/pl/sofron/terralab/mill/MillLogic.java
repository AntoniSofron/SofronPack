package pl.sofron.terralab.mill;

import java.util.function.IntFunction;

/** Pure mechanical rules, shared by the server block and regression tests. */
public final class MillLogic {
    public static final int MAX_SHAFT = 32;
    public static final int CAPACITY = 8;
    public static final int GRIND_TICKS = 80;
    public enum Part { SHAFT, SAILS, OTHER }

    private MillLogic() {}

    public static boolean powered(IntFunction<Part> above) {
        for (int distance = 1; distance <= MAX_SHAFT + 1; distance++) {
            Part part = above.apply(distance);
            if (part == Part.SAILS) return distance >= 2;
            if (part != Part.SHAFT || distance > MAX_SHAFT) return false;
        }
        return false;
    }

    public static boolean canGrind(int input, int output) {
        return input > 0 && output < CAPACITY;
    }

    public static int insertAmount(int input, int offered) {
        return Math.max(0, Math.min(CAPACITY - input, offered));
    }
}
