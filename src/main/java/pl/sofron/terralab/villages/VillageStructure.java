package pl.sofron.terralab.villages;

import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.pools.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;

public final class VillageStructure extends Structure {
    private static final MapCodec<VillageStructure> CODEC = simpleCodec(VillageStructure::new);
    private static final StructureType<VillageStructure> TYPE = () -> CODEC;
    private static final String PREFIX = "sofronpack:village/plains/";
    private static final String VILLAGER = "minecraft:village/plains/villagers/unemployed";

    public static void register() {
        Registry.register(BuiltInRegistries.STRUCTURE_PIECE, Identifier.parse("sofronpack:village_ground"), VillageGround.TYPE);
        Registry.register(BuiltInRegistries.STRUCTURE_TYPE, Identifier.parse("sofronpack:village"), TYPE);
    }
    private VillageStructure(StructureSettings settings) { super(settings); }
    @Override public StructureType<?> type() { return TYPE; }

    private static int height(GenerationContext c, int x, int z) {
        return c.chunkGenerator().getFirstOccupiedHeight(x,z,Heightmap.Types.OCEAN_FLOOR_WG,c.heightAccessor(),c.randomState());
    }

    @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext c) {
        int x = c.chunkPos().getMiddleBlockX(), z = c.chunkPos().getMiddleBlockZ();
        int y = height(c,x,z);
        if (y < c.chunkGenerator().getSeaLevel() || y > 200) return Optional.empty();
        var center = new BlockPos(x,y,z);
        var layout = new Layout(c, center, Rotation.getRandom(c.random()));
        layout.plan();
        if (!layout.fitsTerrain()) return Optional.empty();
        return Optional.of(new GenerationStub(center, layout::addTo));
    }

    private static final class Layout {
        final GenerationContext context;
        final BlockPos center;
        final Rotation orientation;
        final List<PoolElementStructurePiece> buildings = new ArrayList<>();
        final List<VillageGround> ground = new ArrayList<>();

        Layout(GenerationContext context, BlockPos center, Rotation orientation) {
            this.context = context;
            this.center = center;
            this.orientation = orientation;
        }

        BlockPos position(int x, int y, int z) {
            return center.offset(new BlockPos(x,y,z).rotate(orientation));
        }

        void add(String id, BlockPos origin, Rotation rotation) {
            var manager = context.structureTemplateManager();
            var element = StructurePoolElement.single(id).apply(StructureTemplatePool.Projection.RIGID);
            buildings.add(new PoolElementStructurePiece(manager,element,origin,1,rotation,
                    element.getBoundingBox(manager,origin,rotation),LiquidSettings.IGNORE_WATERLOGGING));
        }

        void lot(String name, int x, int y, int z, Rotation local) {
            add(PREFIX + name, position(x,y,z), orientation.getRotated(local));
            var box = buildings.getLast().getBoundingBox();
            int support = Math.min(center.getY(), box.minY()-1);
            ground.add(new VillageGround(new BlockPos(box.minX(),support,box.minZ()),
                    new BlockPos(box.maxX(),support,box.maxZ()), VillageGround.Surface.FOUNDATION, Direction.NORTH));
        }

        void resident(int x,int y,int z) {
            add(VILLAGER,position(x,y,z),orientation);
        }

        private record House(String model, int x, int y, int z, Rotation rotation, int residentX, int residentZ) {}

        private static final List<House> HOUSES = List.of(
                new House("house", -17,0,-10, Rotation.NONE, -16,-8),
                new House("house_corner_alt", -13,0,4, Rotation.CLOCKWISE_90, -14,6),
                new House("house_corner", -23,0,6, Rotation.CLOCKWISE_180, -26,4),
                new House("house_farm", -1,1,13, Rotation.CLOCKWISE_90, -5,12),
                new House("house_farm_alt", 9,0,13, Rotation.CLOCKWISE_90, 7,14),
                new House("house", 17,0,10, Rotation.CLOCKWISE_180, 16,8));

        private record Farm(String model, int x, int z) {}
        private static final List<Farm> FARMS = List.of(
                new Farm("wheat_farm", 18,19),
                new Farm("potato_carrot_farm", 18,-4),
                new Farm("potato_carrot_farm", 28,-4));

        private <T> List<T> selectLots(List<T> candidates, int minimum) {
            var available = new ArrayList<>(candidates);
            int count = minimum + context.random().nextInt(candidates.size() - minimum + 1);
            for (int i = 0; i < count; i++) {
                java.util.Collections.swap(available, i, i + context.random().nextInt(available.size()-i));
            }
            return available.subList(0,count);
        }

        void plan() {
            // Local ground level is zero. Align each doorway or farm gate with the ring or a spur.
            lot("square_fountain",-7,0,-7,Rotation.NONE);
            lot("church",12,0,-39,Rotation.CLOCKWISE_90);
            boolean westSpur = false;
            for (House house : selectLots(HOUSES, 3)) {
                lot(house.model, house.x, house.y, house.z, house.rotation);
                resident(house.residentX, 1, house.residentZ);
                if (house.x == -23) westSpur = true;
            }
            for (Farm farm : selectLots(FARMS, 1)) {
                lot(farm.model, farm.x, 0, farm.z, Rotation.COUNTERCLOCKWISE_90);
            }
            lot("plain_well",33,-4,-2,Rotation.NONE);
            for (int x : new int[]{-5,5}) for (int z : new int[]{-5,5}) {
                var rotation = orientation.getRotated(Rotation.getRandom(context.random()));
                add(PREFIX+"square_oak",position(x,2,z).subtract(new BlockPos(2,0,2).rotate(rotation)),rotation);
            }
            roads(westSpur);
        }

        // Plan the union before edging it, so junctions stay open across chunk boundaries.
        static boolean path(int x, int z, boolean westSpur) {
            int radius = Math.max(Math.abs(x), Math.abs(z));
            return (radius >= 8 && radius <= 11)
                    || (Math.abs(z) <= 2 && (x >= 12 && x <= 32 || westSpur && x <= -12 && x >= -22));
        }

        static VillageGround.Surface paving(int x, int z, boolean westSpur) {
            if (path(x,z,westSpur)) return VillageGround.Surface.PATH;
            if (Math.max(Math.abs(x),Math.abs(z)) < 8) return null; // authored square already has edging
            for (int dx=-1; dx<=1; dx++) for (int dz=-1; dz<=1; dz++) {
                if (path(x+dx,z+dz,westSpur)) return VillageGround.Surface.CURB;
            }
            return null;
        }

        void roads(boolean westSpur) {
            for (int z=-12; z<=12; z++) {
                int x=-23;
                while (x<=33) {
                    int start=x;
                    var surface=paving(x++,z,westSpur);
                    while (x<=33 && paving(x,z,westSpur)==surface) x++;
                    if (surface!=null) ground.add(new VillageGround(position(start,0,z),
                            position(x-1,0,z),surface,Direction.NORTH));
                }
            }
        }

        boolean fitsTerrain() {
            Map<Long,Integer> heights = new HashMap<>();
            for (var strip : ground) {
                var box = strip.getBoundingBox();
                for (int x=box.minX();; x=Math.min(x+5,box.maxX())) {
                    for (int z=box.minZ();; z=Math.min(z+5,box.maxZ())) {
                        final int sx=x, sz=z;
                        int h=heights.computeIfAbsent(((long)x<<32)|Integer.toUnsignedLong(z),
                                ignored -> height(context,sx,sz));
                        if (h < context.chunkGenerator().getSeaLevel() || Math.abs(h-center.getY())>4) return false;
                        if (z==box.maxZ()) break;
                    }
                    if (x==box.maxX()) break;
                }
            }
            return true;
        }

        void addTo(StructurePiecesBuilder builder) {
            ground.forEach(builder::addPiece);
            buildings.forEach(builder::addPiece);
        }
    }
}
