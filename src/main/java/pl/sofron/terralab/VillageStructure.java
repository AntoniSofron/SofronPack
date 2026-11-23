package pl.sofron.terralab;

import com.mojang.serialization.MapCodec;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.pools.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import static pl.sofron.terralab.VillageGround.Surface.*;

/** Plans an entire settlement from the terrain generator before any chunk places blocks. */
public final class VillageStructure extends Structure {
    private static final MapCodec<VillageStructure> CODEC = simpleCodec(VillageStructure::new);
    private static final StructureType<VillageStructure> TYPE = () -> CODEC;
    private static final String HOUSES = "minecraft:village/plains/houses/plains_";

    private static final String FOUNTAIN = "minecraft:village/plains/town_centers/plains_fountain_01";
    private static final ResourceKey<StructureProcessorList> SQUARE_PAVING = ResourceKey.create(
            Registries.PROCESSOR_LIST, Identifier.parse("sofronpack:square_paving"));

    static void register() {
        Registry.register(BuiltInRegistries.STRUCTURE_PIECE, Identifier.parse("sofronpack:village_ground"), VillageGround.TYPE);
        Registry.register(BuiltInRegistries.STRUCTURE_TYPE, Identifier.parse("sofronpack:village"), TYPE);
    }
    private VillageStructure(StructureSettings settings) { super(settings); }
    @Override public StructureType<?> type() { return TYPE; }

    private record Terrain(GenerationContext c, Map<Long,Integer> heights) {
        Terrain(GenerationContext c) { this(c, new HashMap<>()); }

        int height(int x, int z) {
            long key = ((long)x << 32) | Integer.toUnsignedLong(z);
            return heights.computeIfAbsent(key, ignored -> dryGround(c,x,z));
        }
        int height(BlockPos p) { return height(p.getX(),p.getZ()); }

        boolean streetFits(BlockPos start, Direction out, int length) {
            int base = height(start);
            for (int i=2; i<=length; i+=2) {
                BlockPos p = start.relative(out,i);
                int h = height(p);
                if (h<60 || h>200 || Math.abs(h-base)>5 ||
                        Math.abs(height(p.relative(out.getClockWise()))-h)>3) return false;
                if (i%12==0) base=h;
            }
            return true;
        }

        boolean routesFit(BlockPos center, Direction out) {
            BlockPos junction = center.relative(out,50);
            Direction side = out.getClockWise();
            return streetFits(center.relative(out,14),out,36)
                    && streetFits(center.relative(out.getOpposite(),14),out.getOpposite(),36)
                    && streetFits(junction,side,12) && streetFits(junction,side.getOpposite(),12)
                    && Math.abs(height(center.relative(out.getOpposite(),54))
                            -height(center.relative(out.getOpposite(),50)))<=3;
        }
    }

    private static int dryGround(GenerationContext c, int x, int z) {
        int land = c.chunkGenerator().getFirstOccupiedHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG,
                c.heightAccessor(), c.randomState());
        if (land<c.chunkGenerator().getSeaLevel() && c.chunkGenerator().getFirstOccupiedHeight(x,z,
                Heightmap.Types.WORLD_SURFACE_WG,c.heightAccessor(),c.randomState())>land) return -1000;
        return land;
    }

    @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext c) {
        int cx = c.chunkPos().getMiddleBlockX(), cz = c.chunkPos().getMiddleBlockZ();
        Terrain terrain = new Terrain(c);
        Direction preferred = Direction.from2DDataValue(c.random().nextInt(4));
        Direction bestAxis = null;
        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int dx : new int[]{0,-14,14}) for (int dz : new int[]{0,-14,14}) {
            int x = cx + dx, z = cz + dz, y = terrain.height(x,z);
            if (y < 60 || y > 200) continue;
            int relief = 0;
            for (int i : new int[]{-14,0,14}) for (int j : new int[]{-14,0,14}) {
                int h = terrain.height(x+i,z+j);
                if (h < 60 || h > 200) relief = 100;
                relief = Math.max(relief, Math.abs(h-y));
            }
            int score = y + 5 * relief;
            if (relief <= 3 && score < bestScore) {
                BlockPos candidate = new BlockPos(x,y,z);
                Direction axis = preferred;
                for (int turn=0; turn<4; turn++, axis=axis.getClockWise()) {
                    if (terrain.routesFit(candidate,axis)) {
                        best = candidate;
                        bestScore = score;
                        bestAxis = axis;
                        break;
                    }
                }
            }
        }
        if (best == null || c.random().nextFloat() < (best.getY()-60)/200f) return Optional.empty();
        BlockPos center = best;
        Direction axis = bestAxis;
        return Optional.of(new GenerationStub(center, pieces -> new Layout(c,pieces,center,terrain).build(axis)));
    }

    private static Rotation rotation(Direction forward) {
        return switch (forward) {
            case EAST -> Rotation.NONE;
            case SOUTH -> Rotation.CLOCKWISE_90;
            case WEST -> Rotation.CLOCKWISE_180;
            case NORTH -> Rotation.COUNTERCLOCKWISE_90;
            default -> throw new IllegalArgumentException();
        };
    }

    private static final class Layout {
        final GenerationContext c;
        final StructurePiecesBuilder pieces;
        final BlockPos center;
        final List<PoolElementStructurePiece> buildings = new ArrayList<>();
        final Terrain terrain;
        Layout(GenerationContext c, StructurePiecesBuilder pieces, BlockPos center, Terrain terrain) {
            this.c = c; this.pieces = pieces; this.center = center; this.terrain = terrain;
        }

        void rect(BlockPos a, BlockPos b, VillageGround.Surface material) {
            pieces.addPiece(new VillageGround(a,b,material,Direction.NORTH));
        }

        void build(Direction axis) {
            int r = 11;
            rect(center.offset(-r,0,-r),center.offset(r,0,r),STONE);
            for (Direction out : List.of(Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST)) {
                Direction side = out.getClockWise();
                rect(center.relative(out,12).relative(side,-11),center.relative(out,14).relative(side,14),PATH);
                for (int offset : new int[]{-7,7}) {
                    house(center.relative(out,15).relative(side,offset),out,false,false);
                }
            }
            boolean market = c.random().nextBoolean();
            template(FOUNTAIN,
                    center.offset(market ? -10 : -4,0,-4),Rotation.NONE);
            if (market) {
                house(center.offset(1,0,-6),Direction.EAST,false,true);
                house(center.offset(1,0,6),Direction.EAST,false,true);
                well(center.offset(5,0,0));
            }
            road(axis,c.random().nextInt(4)!=0,false);
            road(axis.getOpposite(),false,!market);
            buildings.forEach(pieces::addPiece);
        }

        void well(BlockPos middle) {
            template("minecraft:desert_well/well",middle.offset(-2,-2,-2),Rotation.NONE);
        }

        boolean fits(BoundingBox box, int y) {
            for (int x : new int[]{box.minX(),box.maxX()}) for (int z : new int[]{box.minZ(),box.maxZ()}) {
                int h = terrain.height(x,z);
                if (h<60 || h>200 || Math.abs(h-y)>4) return false;
            }
            return true;
        }

        PoolElementStructurePiece piece(String model, BlockPos origin, Rotation rot) {
            var factory = model.equals(FOUNTAIN)
                    ? StructurePoolElement.single(model,c.registryAccess().getOrThrow(SQUARE_PAVING))
                    : StructurePoolElement.single(model);
            var element = factory.apply(StructureTemplatePool.Projection.RIGID);
            var manager = c.structureTemplateManager();
            return new PoolElementStructurePiece(manager,element,origin,1,rot,
                    element.getBoundingBox(manager,origin,rot),LiquidSettings.IGNORE_WATERLOGGING);
        }
        void template(String model, BlockPos origin, Rotation rot) { buildings.add(piece(model,origin,rot)); }

        void house(BlockPos entrance, Direction intoLot, boolean farm, boolean onSquare) {
            String name = farm ? "small_farm_1" : "small_house_"+(1+c.random().nextInt(4));
            Rotation rot = rotation(intoLot);
            BlockPos origin = entrance.subtract(new BlockPos(0,0,farm ? 4 : 3).rotate(rot));
            var house = piece(HOUSES+name,origin,rot);
            var box = house.getBoundingBox();
            if (!onSquare) {
                if (!fits(box,entrance.getY())) return;
                rect(new BlockPos(box.minX(),entrance.getY()-1,box.minZ()),
                        new BlockPos(box.maxX(),entrance.getY()-1,box.maxZ()),FOUNDATION);
            }
            buildings.add(house);
            if (!farm) template("minecraft:village/plains/villagers/unemployed",
                    entrance.relative(intoLot,3),rot);
        }

        void road(Direction out, boolean junction, boolean endWell) {
            Direction side = out.getClockWise();
            BlockPos start = center.relative(out,14);
            int y = center.getY();
            for (int section=0; section<3; section++) {
                int length = 12;
                BlockPos next = start.relative(out,length);
                int target = Math.clamp(terrain.height(next), Math.max(60,y-5),Math.min(200,y+5));
                line(start,out,length,y,target);
                y = target;
                start = new BlockPos(next.getX(),y,next.getZ());
                if (section<2) for (int sign : new int[]{-1,1}) {
                    Direction facing = sign == 1 ? side : side.getOpposite();
                    house(start.relative(facing,sign==1 ? 2 : 1),facing,
                            c.random().nextInt(4)<section+1,false);
                }
            }
            if (junction) for (Direction turn : List.of(side,side.getOpposite())) {
                BlockPos turnStart = start.relative(turn, turn==side ? 1 : 0);
                // Two parallel lanes meet the last two slices of the main street.
                if (turn.getClockWise()==out) turnStart=turnStart.relative(out,-1);
                BlockPos far = turnStart.relative(turn,12);
                int target=Math.clamp(terrain.height(far), Math.max(60,y-4), Math.min(200,y+4));
                line(turnStart,turn,12,y,target);
                house(new BlockPos(far.getX(),target,far.getZ()).relative(turn),turn,true,false);
            }
            if (endWell) {
                BlockPos pad=start.relative(out,4);
                rect(pad.offset(-3,0,-3),pad.offset(3,0,3),STONE);
                well(pad);
            }
        }

        void line(BlockPos start, Direction out, int length, int from, int to) {
            int delta=to-from;
            int rise=Math.abs(delta);
            int sign=Integer.signum(delta);
            for(int i=1;i<=length;i++) {
                // Leave two level slices at either end for entrances and cross streets.
                int run=length-4;
                int step=Math.clamp(i-2,0,run);
                int height;
                var surface=PATH;
                Direction uphill=delta>=0 ? out : out.getOpposite();
                if (rise>=4) {
                    int level=step*rise/run;
                    int previous=Math.clamp(i-3,0,run)*rise/run;
                    height=from+sign*level;
                    if (level!=previous) {
                        surface=STAIR;
                        height=Math.max(height,from+sign*previous);
                    }
                } else {
                    int half=Math.round(2f*delta*step/run);
                    height=from+Math.floorDiv(half+1,2);
                    if ((half&1)!=0) surface=SLAB;
                }
                BlockPos p=start.relative(out,i);
                p=new BlockPos(p.getX(),height,p.getZ());
                pieces.addPiece(new VillageGround(p,p.relative(out.getClockWise()),surface,uphill));
            }
        }
    }
}
