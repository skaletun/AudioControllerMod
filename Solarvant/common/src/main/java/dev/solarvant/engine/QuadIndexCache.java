package dev.solarvant.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reuses canonical triangle indices for quad-based terrain meshes.
 *
 * Not thread-safe: Minecraft's mesh-to-GPU upload path runs on the render
 * thread. Callers must consume the returned read-only *view* before requesting
 * a larger mesh. Growth never modifies a buffer previously returned to GL.
 * This class never reorders transparent quads: explicitly sorted mesh indices
 * take priority over these canonical indices.
 */
public final class QuadIndexCache {
    private ByteBuffer shortIndices = ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder());
    private ByteBuffer intIndices = ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder());
    private int shortQuads;
    private int intQuads;

    public ByteBuffer quadTriangles(int vertexCount, int indexStride) {
        if (vertexCount < 0 || vertexCount % 4 != 0)
            throw new IllegalArgumentException("Quad vertices must be divisible by four");
        if (indexStride != 2 && indexStride != 4)
            throw new IllegalArgumentException("Only unsigned 16/32-bit index data are supported");
        if (indexStride == 2 && vertexCount > 65535)
            throw new IllegalArgumentException("16-bit indices cannot address all vertices");
        int quads = vertexCount / 4;
        int needed = Math.multiplyExact(Math.multiplyExact(quads, 6), indexStride);
        ByteBuffer current = indexStride == 2 ? shortIndices : intIndices;
        int available = indexStride == 2 ? shortQuads : intQuads;
        if (current.capacity() < needed) {
            int capacity = current.capacity();
            while (capacity < needed) capacity = Math.multiplyExact(capacity, 2);
            ByteBuffer next = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
            ByteBuffer previous = current.duplicate().order(ByteOrder.nativeOrder());
            previous.clear().limit(Math.multiplyExact(Math.multiplyExact(available, 6), indexStride));
            next.put(previous);
            current = next;
        }
        for (int q = available; q < quads; q++) {
            int vertex = q * 4;
            if (indexStride == 2) {
                current.putShort((short)vertex).putShort((short)(vertex + 1));
                current.putShort((short)(vertex + 2)).putShort((short)(vertex + 2));
                current.putShort((short)(vertex + 3)).putShort((short)vertex);
            } else {
                current.putInt(vertex).putInt(vertex + 1);
                current.putInt(vertex + 2).putInt(vertex + 2);
                current.putInt(vertex + 3).putInt(vertex);
            }
        }
        if (indexStride == 2) { shortIndices = current; shortQuads = Math.max(quads, available); }
        else { intIndices = current; intQuads = Math.max(quads, available); }
        ByteBuffer view = current.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
        view.position(0).limit(needed);
        return view;
    }
}
