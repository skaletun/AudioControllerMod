package dev.solarvant.tests;

import dev.solarvant.engine.QuadIndexCache;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;

public final class QuadIndexCacheTests {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void invalid(Runnable operation) {
        try { operation.run(); throw new AssertionError("Invalid input was accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) {
        QuadIndexCache cache = new QuadIndexCache();
        int[] triangle = {0,1,2,2,3,0};
        for (int width : new int[]{2,4}) {
            ByteBuffer a = cache.quadTriangles(4, width);
            require(a.isReadOnly(), "Must not expose mutable cached indices");
            require(a.remaining() == width * 6, "Index size mismatch");
            for (int i = 0; i < triangle.length; i++)
                require((width == 2 ? Short.toUnsignedInt(a.getShort(i*2)) : a.getInt(i*4)) == triangle[i], "Bad quad winding");
            try { a.put((byte)1); throw new AssertionError("Writable view"); }
            catch (ReadOnlyBufferException expected) { }
            ByteBuffer b = cache.quadTriangles(1000, width);
            require(b.remaining() == 1500 * width, "Growth mismatch");
            require(a.remaining() == width * 6, "Old views changed after growth");
            int lastBase = 996;
            int[] last = {lastBase,lastBase+1,lastBase+2,lastBase+2,lastBase+3,lastBase};
            int offset = (249 * 6)*width;
            for(int i=0;i<6;i++) require((width==2?Short.toUnsignedInt(b.getShort(offset+i*2)):b.getInt(offset+i*4))==last[i], "Bad last quad");
            ByteBuffer c = cache.quadTriangles(12, width);
            require(c.remaining() == 18 * width, "Smaller view must be bounded");
            ByteBuffer d = cache.quadTriangles(1000, width);
            require(d.remaining()==1500*width, "Cache must not shrink");
        }
        invalid(() -> cache.quadTriangles(5, 2));
        invalid(() -> cache.quadTriangles(-4, 4));
        invalid(() -> cache.quadTriangles(8, 1));
        invalid(() -> cache.quadTriangles(65536, 2));
        require(cache.quadTriangles(0, 2).remaining()==0, "Empty mesh");
        System.out.println("PASS: cached 16-bit and 32-bit quad indices, growth, bounds, immutable views");
    }
}
