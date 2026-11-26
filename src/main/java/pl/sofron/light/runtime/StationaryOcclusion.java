package pl.sofron.light.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pl.sofron.light.engine.LightOcclusion;

/** Immutable column masks shared by the client index and the background light worker.
 * A null section is transparent, an empty bit array is entirely opaque. */
public final class StationaryOcclusion implements LightOcclusion {
    public static final long[] FULL = new long[0];
    public static final class Column {
        private final int minSection;
        private final long[][] sections;
        public Column(int minSection, long[][] sections) {
            this.minSection=minSection; this.sections=sections.clone();
        }
        boolean blocks(int x,int y,int z) {
            int s=(y>>4)-minSection;
            if(s<0||s>=sections.length)return true;
            long[] bits=sections[s];
            if(bits==null)return false;
            if(bits.length==0)return true;
            int at=((y&15)*16+(z&15))*16+(x&15);
            return (bits[at>>6]&(1L<<(at&63)))!=0;
        }
        boolean same(Column other){return other!=null&&minSection==other.minSection&&Arrays.deepEquals(sections,other.sections);}
    }
    private final Map<Long,Column> columns = new HashMap<>();
    private boolean known;
    public StationaryOcclusion snapshot(){
        var copy=new StationaryOcclusion();
        copy.columns.putAll(columns);copy.known=known;return copy;
    }
    private static long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    public boolean replace(int x,int z,Column column){
        known=true;
        long key=key(x,z);Column old=columns.get(key);
        if(column.same(old))return false;
        columns.put(key,column);return true;
    }
    public boolean remove(int x,int z){return columns.remove(key(x,z))!=null;}
    public void clear(){columns.clear();known=false;}
    public boolean known(){return known;}
    @Override public boolean blocks(int x,int y,int z){
        Column c=columns.get(key(x>>4,z>>4));
        return c==null?known:c.blocks(x,y,z);
    }
    /** Cache key: identical column objects imply identical immutable geometry. */
    public List<Column> neighborhood(int cx,int cz,int reach){
        var result=new ArrayList<Column>((2*reach+1)*(2*reach+1));
        for(int z=cz-reach;z<=cz+reach;z++)for(int x=cx-reach;x<=cx+reach;x++)
            result.add(columns.get(key(x,z)));
        return result;
    }
    public static void set(long[] bits,int x,int y,int z){
        int at=((y&15)*16+(z&15))*16+(x&15);
        bits[at>>6]|=1L<<(at&63);
    }
}