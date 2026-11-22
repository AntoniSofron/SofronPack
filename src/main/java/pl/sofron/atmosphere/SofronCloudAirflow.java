package pl.sofron.atmosphere;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shared per-tick wind physics for the three existing SofronPack cloud decks.
 *
 * Exactly the same 12-block cloud cells, scrolling clock and independent
 * per-deck offsets used by CloudRenderer are sampled on either logical side.
 * This modifies a single MOVE displacement rather than compounding entity
 * velocity every tick, so a sustained flight never accelerates exponentially.
 */
public final class SofronCloudAirflow {
    static final double CELL_BLOCKS = 12.0;
    // Use the precise vanilla float constant, not 0.03 as a double.
    static final double CLOUD_DRIFT_BLOCKS_PER_TICK = 0.030000001f;
    static final double CLOUD_Z_OFFSET = 3.96;
    static final double CLOUD_THICKNESS = 8.0;
    public static final double CLOUD_FLIGHT_FACTOR = 0.50;
    public static final double CLOUD_FALL_FACTOR = 0.70;
    private static final long[] CLOUD_TICK_OFFSETS = {0, 23L * 400L, 47L * 400L};

    private SofronCloudAirflow() {}

    /**
     * A plane is not itself the entire cloud: its alpha-texture cells define
     * which 12x12 areas contain cloud. Vanilla and the Sofron renderer use
     * the same default texture and clock, so players can fly through gaps.
     */
    private record Mask(int width, int height, boolean[] opaque) {
        boolean at(int x, int z) {
            return opaque[Math.floorMod(x, width) + Math.floorMod(z, height) * width];
        }
    }

    private static final class CloudTexture {
        static final Mask MASK = loadVanillaClouds();
    }

    /** Start atlas decoding before someone first flies into a cloud. */
    public static void warmUp() {
        Thread.ofVirtual().name("SofronPack-Cloud-Mask").start(() -> {
            // One-time, render-backend-independent resource decoding.
            Mask ignored = CloudTexture.MASK;
        });
    }

    private static Mask loadVanillaClouds() {
        // This is a standard Minecraft client resource, not a new texture
        // distributed or modified by SofronPack. In an integrated server its
        // bundled default assets are visible to the same Java classloader.
        try (InputStream source = SofronCloudAirflow.class.getResourceAsStream(
                "/assets/minecraft/textures/environment/clouds.png")) {
            if (source != null) {
                BufferedImage image = ImageIO.read(source);
                if (image != null && image.getWidth() > 0 && image.getHeight() > 0) {
                    int width = image.getWidth(), height = image.getHeight();
                    boolean[] mask = new boolean[width * height];
                    int solid = 0;
                    for (int z = 0; z < height; z++) {
                        for (int x = 0; x < width; x++) {
                            boolean filled = (image.getRGB(x, z) >>> 24) >= 10;
                            mask[x + z * width] = filled;
                            if (filled) solid++;
                        }
                    }
                    System.out.println("[SofronPack] Cloud airflow pattern ready: "
                            + width + "x" + height + ", occupied cells " + solid);
                    return new Mask(width, height, mask);
                }
            }
        } catch (IOException error) {
            System.err.println("[SofronPack] Cloud mask resource: " + error.getMessage());
        }
        // A dedicated game server may not ship client artwork. Keep its
        // motion deterministic and server-authoritative rather than trusting
        // an arbitrary client's resource pack; cloud planes then behave as
        // continuous 8-block atmospheric layers on that server.
        System.err.println("[SofronPack] Cloud texture unavailable; using full cloud-deck wind");
        return new Mask(1, 1, new boolean[] {true});
    }

    public static double altitudeFactor(double y) {
        if (y < CloudBandLayout.LOW) return 1.0;
        if (y < CloudBandLayout.MIDDLE) return 1.25;
        if (y < CloudBandLayout.HIGH) return 1.50;
        return 1.75;
    }

    static int cloudColumn(double worldX, long timeTicks, int layer) {
        return (int)Math.floor((worldX +
                (timeTicks + CLOUD_TICK_OFFSETS[layer]) * CLOUD_DRIFT_BLOCKS_PER_TICK)
                / CELL_BLOCKS);
    }

    static int cloudColumn(double worldX, long timeTicks, int layer, int textureWidth) {
        // Match the vanilla renderer's periodic time wrapping. Without it,
        // long-running server worlds eventually lose cloud-position precision.
        long clock = Math.floorMod(timeTicks + CLOUD_TICK_OFFSETS[layer],
                (long)textureWidth * 400L);
        return (int)Math.floor((worldX +
                ((float)clock) * CLOUD_DRIFT_BLOCKS_PER_TICK) / CELL_BLOCKS);
    }

    static int cloudRow(double worldZ) {
        return (int)Math.floor((worldZ + CLOUD_Z_OFFSET) / CELL_BLOCKS);
    }

    public static boolean inCloud(AABB bounds, long worldTime) {
        // No texture decode, allocations or cloud-cell lookups at normal
        // gameplay altitudes. Only the eight-block cloud slabs need the mask.
        double minY = bounds.minY, maxY = bounds.maxY;
        if (maxY <= CloudBandLayout.LOW
                || (minY >= CloudBandLayout.LOW + CLOUD_THICKNESS
                    && maxY <= CloudBandLayout.MIDDLE)
                || (minY >= CloudBandLayout.MIDDLE + CLOUD_THICKNESS
                    && maxY <= CloudBandLayout.HIGH)
                || minY >= CloudBandLayout.HIGH + CLOUD_THICKNESS)
            return false;
        Mask mask = CloudTexture.MASK;
        // Sample the actual entity footprint so a four-block-wide mount can
        // touch a cloud even if its centre is in a gap.
        double cx = (bounds.minX + bounds.maxX) * 0.5;
        double cz = (bounds.minZ + bounds.maxZ) * 0.5;
        double[] xs = {bounds.minX, cx, bounds.maxX - 0.001};
        double[] zs = {bounds.minZ, cz, bounds.maxZ - 0.001};
        for (int layer = 0; layer < CloudBandLayout.BAND_COUNT; layer++) {
            double y = layer == 0 ? CloudBandLayout.LOW
                    : layer == 1 ? CloudBandLayout.MIDDLE : CloudBandLayout.HIGH;
            if (maxY <= y || minY >= y + CLOUD_THICKNESS) continue;
            for (double testX : xs) for (double testZ : zs) {
                int cellX = cloudColumn(testX, worldTime, layer, mask.width);
                if (mask.at(cellX, cloudRow(testZ))) return true;
            }
        }
        return false;
    }

    /** A single displacement transformation, not a cumulative velocity multiplier. */
    public static Vec3 transform(Vec3 motion, double altitude, boolean poweredFlight,
                                 boolean freeFall, boolean inCloud) {
        if (!poweredFlight && !freeFall) return motion;
        if (poweredFlight) {
            double factor = altitudeFactor(altitude) * (inCloud ? CLOUD_FLIGHT_FACTOR : 1.0);
            if (factor == 1.0 && !inCloud) return motion;
            double vertical = inCloud && freeFall
                    ? motion.y * CLOUD_FALL_FACTOR : motion.y * factor;
            return new Vec3(motion.x * factor, vertical, motion.z * factor);
        }
        return inCloud && motion.y < 0
                ? new Vec3(motion.x, motion.y * CLOUD_FALL_FACTOR, motion.z)
                : motion;
    }

    public static Vec3 modifyMovement(Entity entity, MoverType mover, Vec3 delta) {
        // Every moving entity calls this method, but cloud winds affect only
        // players and piloted happy ghasts. Keep all other paths nearly free.
        if (mover != MoverType.SELF
                || (!(entity instanceof Player) && !(entity instanceof HappyGhast))
                || !entity.level().dimension().equals(Level.OVERWORLD))
            return delta;
        Player player = entity instanceof Player p ? p : null;
        boolean gliding = player != null && player.isFallFlying();
        boolean flying = player != null
                && (player.isSpectator() || player.getAbilities().flying || gliding);
        if (player != null && player.isPassenger()) return delta;
        boolean riddenGhast = entity instanceof HappyGhast ghast
                && ghast.getControllingPassenger() instanceof Player;
        boolean active = flying || riddenGhast;
        boolean falling = player != null && delta.y < 0 && !player.getAbilities().flying;
        if (!active && !falling) return delta;
        AABB bounds = entity.getBoundingBox();
        double y = (bounds.minY + bounds.maxY) / 2.0;
        // A fast glider or falling player may cross all eight cloud blocks
        // within a single tick. Check the swept body as well as the starting
        // position, otherwise very high-speed falling would bypass the brake.
        AABB traversed = bounds.expandTowards(delta);
        boolean cloud = inCloud(traversed, entity.level().getGameTime());
        return transform(delta, y, active, falling, cloud);
    }
}
