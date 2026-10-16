package cn.ussshenzhou.notenoughbandwidth.mixin.early;

import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.network.play.server.S26PacketMapChunkBulk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(S26PacketMapChunkBulk.class)
public abstract class MixinS26PacketMapChunkBulk {

    @Unique
    private static final int NEB_MAX_CHUNKS = 1024;

    @Shadow(aliases = "field_149266_a", remap = false)
    private int[] xPositions;

    @Shadow(aliases = "field_149264_b", remap = false)
    private int[] zPositions;

    @Shadow(aliases = "field_149265_c", remap = false)
    private int[] subChunkMasks;

    @Shadow(aliases = "field_149262_d", remap = false)
    private int[] subChunkMSBMasks;

    @Shadow(aliases = "field_149261_g", remap = false)
    private int deflatedSize;

    @Shadow(aliases = "field_149267_h", remap = false)
    private boolean skylight;

    @Shadow(aliases = "field_149260_f", remap = false)
    private byte[][] datas;

    @Inject(method = "readPacketData", at = @At("HEAD"), cancellable = true, require = 0)
    private void neb$readChunkApiFormat(PacketBuffer data, CallbackInfo callback) throws IOException {
        int start = data.readerIndex();
        int end = data.writerIndex();
        int chunkCount = neb$chunkCount(data, start, end);
        if (chunkCount == 0) {
            return;
        }

        int sizesStart = start + 2;
        int deflatedSizeOffset = sizesStart + chunkCount * Integer.BYTES;
        int deflatedSize = data.getInt(deflatedSizeOffset);
        int compressedStart = deflatedSizeOffset + Integer.BYTES + 1;
        int headersStart = compressedStart + deflatedSize;
        if (headersStart + chunkCount * 10 != end) {
            throw new IOException("Invalid ChunkAPI bulk chunk frame length");
        }

        int[] sizes = new int[chunkCount];
        long uncompressedSize = 0;
        for (int i = 0; i < chunkCount; i++) {
            int size = data.getInt(sizesStart + i * Integer.BYTES);
            sizes[i] = size;
            uncompressedSize += size;
        }
        int sectionCapacity = S21PacketChunkData.func_149275_c() * chunkCount;
        if (uncompressedSize > sectionCapacity) {
            throw new IOException("ChunkAPI bulk chunk data exceeds the packet capacity");
        }

        byte[] compressed = new byte[deflatedSize];
        data.getBytes(compressedStart, compressed);
        byte[] uncompressed = inflate(compressed, (int) uncompressedSize);

        xPositions = new int[chunkCount];
        zPositions = new int[chunkCount];
        subChunkMasks = new int[chunkCount];
        subChunkMSBMasks = new int[chunkCount];
        datas = new byte[chunkCount][];
        int dataOffset = 0;
        for (int i = 0; i < chunkCount; i++) {
            int headerOffset = headersStart + i * 10;
            xPositions[i] = data.getInt(headerOffset);
            zPositions[i] = data.getInt(headerOffset + Integer.BYTES);
            subChunkMasks[i] = data.getUnsignedShort(headerOffset + Integer.BYTES * 2);
            datas[i] = Arrays.copyOfRange(uncompressed, dataOffset, dataOffset + sizes[i]);
            dataOffset += sizes[i];
        }
        this.deflatedSize = deflatedSize;
        skylight = data.getByte(deflatedSizeOffset + Integer.BYTES) != 0;
        data.readerIndex(end);
        callback.cancel();
    }

    @Unique
    private static int neb$chunkCount(PacketBuffer data, int start, int end) {
        if (end - start < 2) {
            return 0;
        }
        int count = data.getShort(start);
        if (count <= 0 || count > NEB_MAX_CHUNKS) {
            return 0;
        }
        long minimum = 2L + count * Integer.BYTES + Integer.BYTES + 1L + count * 10L;
        if (minimum > end - start) {
            return 0;
        }
        int sizesStart = start + 2;
        long total = 0;
        int capacity = S21PacketChunkData.func_149275_c() * count;
        for (int i = 0; i < count; i++) {
            int size = data.getInt(sizesStart + i * Integer.BYTES);
            if (size < 0) {
                return 0;
            }
            total += size;
            if (total > capacity) {
                return 0;
            }
        }
        int deflatedSizeOffset = sizesStart + count * Integer.BYTES;
        int deflatedSize = data.getInt(deflatedSizeOffset);
        if (deflatedSize <= 0) {
            return 0;
        }
        int compressedStart = deflatedSizeOffset + Integer.BYTES + 1;
        return compressedStart + deflatedSize + count * 10 == end ? count : 0;
    }

    @Unique
    private static byte[] inflate(byte[] compressed, int expectedSize) throws IOException {
        byte[] result = new byte[expectedSize];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            int size = inflater.inflate(result);
            if (!inflater.finished() || size != expectedSize) {
                throw new IOException("Invalid ChunkAPI bulk chunk compression size");
            }
            return result;
        } catch (DataFormatException exception) {
            throw new IOException("Invalid ChunkAPI bulk chunk compression", exception);
        } finally {
            inflater.end();
        }
    }
}
