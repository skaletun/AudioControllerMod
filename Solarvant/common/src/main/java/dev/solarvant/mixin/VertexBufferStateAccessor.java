package dev.solarvant.mixin;

import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VertexBuffer.class)
public interface VertexBufferStateAccessor {
    @Accessor("mode") void solarvant$setMode(VertexFormat.Mode mode);
    @Accessor("mode") VertexFormat.Mode solarvant$getMode();
    @Accessor("format") void solarvant$setFormat(VertexFormat format);
    @Accessor("indexCount") void solarvant$setIndexCount(int count);
}
