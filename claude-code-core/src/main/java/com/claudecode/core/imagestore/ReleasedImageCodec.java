package com.claudecode.core.imagestore;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;




final class ReleasedImageCodec {

    private static final int[] LUMA_Q = {
        16, 11, 10, 16, 24, 40, 51, 61,
        12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77,
        24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101,
        72, 92, 95, 98, 112, 100, 103, 99
    };
    private static final int[] CHROMA_Q = {
        17, 18, 24, 47, 99, 99, 99, 99,
        18, 21, 26, 66, 99, 99, 99, 99,
        24, 26, 56, 99, 99, 99, 99, 99,
        47, 66, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99
    };
    private static final int[] UNZIGZAG = {
        0, 1, 8, 16, 9, 2, 3, 10,
        17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34,
        27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36,
        29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46,
        53, 60, 61, 54, 47, 55, 62, 63
    };

    private static final int[] LUMA_DC_LENGTHS = {
        0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0
    };
    private static final int[] LUMA_DC_VALUES = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11
    };
    private static final int[] CHROMA_DC_LENGTHS = {
        0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0
    };
    private static final int[] CHROMA_DC_VALUES = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11
    };
    private static final int[] LUMA_AC_LENGTHS = {
        0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 125
    };
    private static final int[] LUMA_AC_VALUES = {
        0x01,0x02,0x03,0x00,0x04,0x11,0x05,0x12,0x21,0x31,0x41,0x06,0x13,0x51,0x61,0x07,
        0x22,0x71,0x14,0x32,0x81,0x91,0xa1,0x08,0x23,0x42,0xb1,0xc1,0x15,0x52,0xd1,0xf0,
        0x24,0x33,0x62,0x72,0x82,0x09,0x0a,0x16,0x17,0x18,0x19,0x1a,0x25,0x26,0x27,0x28,
        0x29,0x2a,0x34,0x35,0x36,0x37,0x38,0x39,0x3a,0x43,0x44,0x45,0x46,0x47,0x48,0x49,
        0x4a,0x53,0x54,0x55,0x56,0x57,0x58,0x59,0x5a,0x63,0x64,0x65,0x66,0x67,0x68,0x69,
        0x6a,0x73,0x74,0x75,0x76,0x77,0x78,0x79,0x7a,0x83,0x84,0x85,0x86,0x87,0x88,0x89,
        0x8a,0x92,0x93,0x94,0x95,0x96,0x97,0x98,0x99,0x9a,0xa2,0xa3,0xa4,0xa5,0xa6,0xa7,
        0xa8,0xa9,0xaa,0xb2,0xb3,0xb4,0xb5,0xb6,0xb7,0xb8,0xb9,0xba,0xc2,0xc3,0xc4,0xc5,
        0xc6,0xc7,0xc8,0xc9,0xca,0xd2,0xd3,0xd4,0xd5,0xd6,0xd7,0xd8,0xd9,0xda,0xe1,0xe2,
        0xe3,0xe4,0xe5,0xe6,0xe7,0xe8,0xe9,0xea,0xf1,0xf2,0xf3,0xf4,0xf5,0xf6,0xf7,0xf8,
        0xf9,0xfa
    };
    private static final int[] CHROMA_AC_LENGTHS = {
        0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 119
    };
    private static final int[] CHROMA_AC_VALUES = {
        0x00,0x01,0x02,0x03,0x11,0x04,0x05,0x21,0x31,0x06,0x12,0x41,0x51,0x07,0x61,0x71,
        0x13,0x22,0x32,0x81,0x08,0x14,0x42,0x91,0xa1,0xb1,0xc1,0x09,0x23,0x33,0x52,0xf0,
        0x15,0x62,0x72,0xd1,0x0a,0x16,0x24,0x34,0xe1,0x25,0xf1,0x17,0x18,0x19,0x1a,0x26,
        0x27,0x28,0x29,0x2a,0x35,0x36,0x37,0x38,0x39,0x3a,0x43,0x44,0x45,0x46,0x47,0x48,
        0x49,0x4a,0x53,0x54,0x55,0x56,0x57,0x58,0x59,0x5a,0x63,0x64,0x65,0x66,0x67,0x68,
        0x69,0x6a,0x73,0x74,0x75,0x76,0x77,0x78,0x79,0x7a,0x82,0x83,0x84,0x85,0x86,0x87,
        0x88,0x89,0x8a,0x92,0x93,0x94,0x95,0x96,0x97,0x98,0x99,0x9a,0xa2,0xa3,0xa4,0xa5,
        0xa6,0xa7,0xa8,0xa9,0xaa,0xb2,0xb3,0xb4,0xb5,0xb6,0xb7,0xb8,0xb9,0xba,0xc2,0xc3,
        0xc4,0xc5,0xc6,0xc7,0xc8,0xc9,0xca,0xd2,0xd3,0xd4,0xd5,0xd6,0xd7,0xd8,0xd9,0xda,
        0xe2,0xe3,0xe4,0xe5,0xe6,0xe7,0xe8,0xe9,0xea,0xf2,0xf3,0xf4,0xf5,0xf6,0xf7,0xf8,
        0xf9,0xfa
    };

    private ReleasedImageCodec() {}

    static BufferedImage resizeLanczos3(BufferedImage source, int width, int height) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        Kernel[] yKernels = kernels(sourceHeight, height);
        Kernel[] xKernels = kernels(sourceWidth, width);
        int maxSourceRows = maximumKernelSize(yKernels);
        SourceRowCache sourceRowCache = new SourceRowCache(source, maxSourceRows, sourceWidth);
        int[][] sourceRows = new int[maxSourceRows][];
        float[] verticalRow = new float[sourceWidth * 4];
        int[] outputRow = new int[width];
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        for (int outY = 0; outY < height; outY++) {
            Kernel yKernel = yKernels[outY];
            sourceRowCache.copyRows(yKernel.left(), sourceRows, yKernel.weights().length);
            filterVerticalRow(sourceRows, yKernel.weights(), verticalRow, sourceWidth);
            filterHorizontalRow(verticalRow, xKernels, outputRow);
            resized.setRGB(0, outY, width, 1, outputRow, 0, width);
        }
        return resized;
    }

    static long estimatedResizeScratchBytes(int sourceWidth, int sourceHeight, int width, int height) {
        int maxYKernel = maximumKernelSize(kernels(sourceHeight, height));
        long sourceRows = (long) maxYKernel * sourceWidth * Integer.BYTES;
        long verticalRow = (long) sourceWidth * 4 * Float.BYTES;
        long outputRow = (long) width * Integer.BYTES;
        long kernelStorage = estimatedKernelBytes(sourceHeight, height)
            + estimatedKernelBytes(sourceWidth, width);
        return sourceRows + verticalRow + outputRow + kernelStorage;
    }

    private static Kernel[] kernels(int sourceSize, int targetSize) {
        Kernel[] kernels = new Kernel[targetSize];
        float ratio = (float) sourceSize / targetSize;
        float scale = Math.max(1.0f, ratio);
        float support = 3.0f * scale;
        for (int output = 0; output < targetSize; output++) {
            float input = (output + 0.5f) * ratio;
            int left = clamp((int) Math.floor(input - support), 0, sourceSize - 1);
            int right = clamp((int) Math.ceil(input + support), left + 1, sourceSize);
            float center = input - 0.5f;
            kernels[output] = new Kernel(left, normalizedWeights(left, right, center, scale));
        }
        return kernels;
    }

    private static void filterVerticalRow(int[][] sourceRows, float[] weights,
                                          float[] verticalRow, int sourceWidth) {
            for (int x = 0; x < sourceWidth; x++) {
                float a = 0, r = 0, g = 0, b = 0;
                for (int index = 0; index < weights.length; index++) {
                int argb = sourceRows[index][x];
                    float weight = weights[index];
                    a += ((argb >>> 24) & 0xff) * weight;
                    r += ((argb >>> 16) & 0xff) * weight;
                    g += ((argb >>> 8) & 0xff) * weight;
                    b += (argb & 0xff) * weight;
                }
            int offset = x * 4;
            verticalRow[offset] = r;
            verticalRow[offset + 1] = g;
            verticalRow[offset + 2] = b;
            verticalRow[offset + 3] = a;
            }
    }

    private static void filterHorizontalRow(float[] verticalRow, Kernel[] kernels, int[] outputRow) {
        for (int outX = 0; outX < kernels.length; outX++) {
            Kernel kernel = kernels[outX];
                float r = 0, g = 0, b = 0, a = 0;
            for (int index = 0; index < kernel.weights().length; index++) {
                int offset = (kernel.left() + index) * 4;
                float weight = kernel.weights()[index];
                r += verticalRow[offset] * weight;
                g += verticalRow[offset + 1] * weight;
                b += verticalRow[offset + 2] * weight;
                a += verticalRow[offset + 3] * weight;
                }
            outputRow[outX] = (roundU8(a) << 24) | (roundU8(r) << 16)
                    | (roundU8(g) << 8) | roundU8(b);
            }
    }

    private static int maximumKernelSize(Kernel[] kernels) {
        int maximum = 0;
        for (Kernel kernel : kernels) maximum = Math.max(maximum, kernel.weights().length);
        return maximum;
    }

    private static long estimatedKernelBytes(int sourceSize, int targetSize) {
        Kernel[] kernels = kernels(sourceSize, targetSize);
        long bytes = (long) targetSize * (Integer.BYTES + 8L);
        for (Kernel kernel : kernels) bytes += (long) kernel.weights().length * Float.BYTES;
        return bytes;
    }

    static byte[] encodeJpeg(BufferedImage image, int quality) {
        return new JpegEncoder(image, quality).encode();
    }

    private static float[] normalizedWeights(int left, int right, float center, float scale) {
        float[] weights = new float[right - left];
        float sum = 0;
        for (int index = 0; index < weights.length; index++) {
            float weight = lanczos3(((left + index) - center) / scale);
            weights[index] = weight;
            sum += weight;
        }
        for (int index = 0; index < weights.length; index++) weights[index] /= sum;
        return weights;
    }

    private static float lanczos3(float value) {
        if (Math.abs(value) >= 3.0f) return 0.0f;
        return sinc(value) * sinc(value / 3.0f);
    }

    private static float sinc(float value) {
        if (value == 0.0f) return 1.0f;
        float angle = value * (float) Math.PI;
        return (float) Math.sin(angle) / angle;
    }

    private static int roundU8(float value) {
        float clamped = Math.max(0.0f, Math.min(255.0f, value));
        return (int) Math.floor(clamped + 0.5f);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class JpegEncoder {
        private final BufferedImage image;
        private final int[] lumaTable;
        private final int[] chromaTable;
        private final HuffmanCode[] lumaDc = buildHuffmanTable(LUMA_DC_LENGTHS, LUMA_DC_VALUES);
        private final HuffmanCode[] lumaAc = buildHuffmanTable(LUMA_AC_LENGTHS, LUMA_AC_VALUES);
        private final HuffmanCode[] chromaDc = buildHuffmanTable(CHROMA_DC_LENGTHS, CHROMA_DC_VALUES);
        private final HuffmanCode[] chromaAc = buildHuffmanTable(CHROMA_AC_LENGTHS, CHROMA_AC_VALUES);
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int accumulator;
        private int bitCount;

        private JpegEncoder(BufferedImage image, int quality) {
            this.image = image;
            int boundedQuality = clamp(quality, 1, 100);
            int scale = boundedQuality < 50 ? 5000 / boundedQuality : 200 - boundedQuality * 2;
            this.lumaTable = scaledTable(LUMA_Q, scale);
            this.chromaTable = scaledTable(CHROMA_Q, scale);
        }

        private byte[] encode() {
            marker(0xd8);
            segment(0xe0, new byte[] {'J','F','I','F',0,1,2,0,0,1,0,1,0,0});
            segment(0xc0, frameHeader());
            segment(0xdb, quantizationSegment(0, lumaTable));
            segment(0xdb, quantizationSegment(1, chromaTable));
            segment(0xc4, huffmanSegment(0, 0, LUMA_DC_LENGTHS, LUMA_DC_VALUES));
            segment(0xc4, huffmanSegment(1, 0, LUMA_AC_LENGTHS, LUMA_AC_VALUES));
            segment(0xc4, huffmanSegment(0, 1, CHROMA_DC_LENGTHS, CHROMA_DC_VALUES));
            segment(0xc4, huffmanSegment(1, 1, CHROMA_AC_LENGTHS, CHROMA_AC_VALUES));
            segment(0xda, new byte[] {3,1,0,2,0x11,3,0x11,0,63,0});
            encodeBlocks();
            writeBits(0x7f, 7);
            marker(0xd9);
            return output.toByteArray();
        }

        private void encodeBlocks() {
            int[] yBlock = new int[64];
            int[] cbBlock = new int[64];
            int[] crBlock = new int[64];
            int[] transformed = new int[64];
            int yPrevious = 0, cbPrevious = 0, crPrevious = 0;
            int width = image.getWidth(), height = image.getHeight();
            for (int y = 0; y < height; y += 8) {
                for (int x = 0; x < width; x += 8) {
                    for (int dy = 0; dy < 8; dy++) {
                        for (int dx = 0; dx < 8; dx++) {
                            int rgb = image.getRGB(Math.min(x + dx, width - 1), Math.min(y + dy, height - 1));
                            int red = (rgb >>> 16) & 0xff;
                            int green = (rgb >>> 8) & 0xff;
                            int blue = rgb & 0xff;
                            int offset = dy * 8 + dx;
                            yBlock[offset] = (19595 * red + 38469 * green + 7471 * blue + 32767) >> 16;
                            cbBlock[offset] = (-11059 * red - 21709 * green + 32768 * blue + 8421375) >> 16;
                            crBlock[offset] = (32768 * red - 27439 * green - 5329 * blue + 8421375) >> 16;
                        }
                    }
                    fdct(yBlock, transformed);
                    quantize(transformed, lumaTable);
                    yPrevious = writeBlock(transformed, yPrevious, lumaDc, lumaAc);
                    fdct(cbBlock, transformed);
                    quantize(transformed, chromaTable);
                    cbPrevious = writeBlock(transformed, cbPrevious, chromaDc, chromaAc);
                    fdct(crBlock, transformed);
                    quantize(transformed, chromaTable);
                    crPrevious = writeBlock(transformed, crPrevious, chromaDc, chromaAc);
                }
            }
        }

        private static void quantize(int[] block, int[] table) {
            for (int index = 0; index < 64; index++) {
                float value = (float) (block[index] / 8) / table[index];
                block[index] = value >= 0 ? (int) Math.floor(value + 0.5f) : (int) Math.ceil(value - 0.5f);
            }
        }

        private int writeBlock(int[] block, int previousDc, HuffmanCode[] dc, HuffmanCode[] ac) {
            int currentDc = block[0];
            Coefficient difference = coefficient(currentDc - previousDc);
            writeHuffman(difference.size(), dc);
            writeBits(difference.value(), difference.size());
            int zeroRun = 0;
            for (int index = 1; index < 64; index++) {
                int value = block[UNZIGZAG[index]];
                if (value == 0) {
                    zeroRun++;
                    continue;
                }
                while (zeroRun > 15) {
                    writeHuffman(0xf0, ac);
                    zeroRun -= 16;
                }
                Coefficient encoded = coefficient(value);
                writeHuffman((zeroRun << 4) | encoded.size(), ac);
                writeBits(encoded.value(), encoded.size());
                zeroRun = 0;
            }
            if (block[UNZIGZAG[63]] == 0) writeHuffman(0, ac);
            return currentDc;
        }

        private void writeHuffman(int value, HuffmanCode[] table) {
            HuffmanCode code = table[value];
            writeBits(code.code(), code.size());
        }

        private void writeBits(int bits, int size) {
            if (size == 0) return;
            bitCount += size;
            accumulator |= bits << (32 - bitCount);
            while (bitCount >= 8) {
                int value = accumulator >>> 24;
                output.write(value);
                if (value == 0xff) output.write(0);
                bitCount -= 8;
                accumulator <<= 8;
            }
        }

        private void marker(int marker) {
            output.write(0xff);
            output.write(marker);
        }

        private void segment(int marker, byte[] data) {
            marker(marker);
            int length = data.length + 2;
            output.write(length >>> 8);
            output.write(length);
            output.writeBytes(data);
        }

        private byte[] frameHeader() {
            int width = image.getWidth(), height = image.getHeight();
            return new byte[] {8, (byte) (height >>> 8), (byte) height,
                (byte) (width >>> 8), (byte) width, 3,
                1,0x11,0, 2,0x11,1, 3,0x11,1};
        }
    }

    private static int[] scaledTable(int[] source, int scale) {
        int[] result = new int[source.length];
        for (int index = 0; index < source.length; index++) {
            result[index] = clamp((source[index] * scale + 50) / 100, 1, 255);
        }
        return result;
    }

    private static byte[] quantizationSegment(int identifier, int[] table) {
        byte[] result = new byte[65];
        result[0] = (byte) identifier;
        for (int index = 0; index < 64; index++) result[index + 1] = (byte) table[UNZIGZAG[index]];
        return result;
    }

    private static byte[] huffmanSegment(int tableClass, int destination, int[] lengths, int[] values) {
        byte[] result = new byte[1 + lengths.length + values.length];
        result[0] = (byte) ((tableClass << 4) | destination);
        for (int index = 0; index < lengths.length; index++) result[index + 1] = (byte) lengths[index];
        for (int index = 0; index < values.length; index++) result[index + 17] = (byte) values[index];
        return result;
    }

    private static HuffmanCode[] buildHuffmanTable(int[] lengths, int[] values) {
        HuffmanCode[] table = new HuffmanCode[256];
        int code = 0;
        int valueIndex = 0;
        for (int size = 1; size <= 16; size++) {
            for (int count = 0; count < lengths[size - 1]; count++) {
                table[values[valueIndex++]] = new HuffmanCode(size, code);
                code++;
            }
            code <<= 1;
        }
        return table;
    }

    private static Coefficient coefficient(int coefficient) {
        int magnitude = Math.abs(coefficient);
        int size = 0;
        while (magnitude > 0) {
            magnitude >>>= 1;
            size++;
        }
        int mask = (1 << size) - 1;
        int value = coefficient < 0 ? (coefficient - 1) & mask : coefficient & mask;
        return new Coefficient(size, value);
    }

    private static void fdct(int[] samples, int[] coefficients) {
        final int constBits = 13;
        final int pass1Bits = 2;
        for (int y = 0; y < 8; y++) {
            int offset = y * 8;
            int t0 = samples[offset] + samples[offset + 7];
            int t1 = samples[offset + 1] + samples[offset + 6];
            int t2 = samples[offset + 2] + samples[offset + 5];
            int t3 = samples[offset + 3] + samples[offset + 4];
            int t10 = t0 + t3, t12 = t0 - t3, t11 = t1 + t2, t13 = t1 - t2;
            t0 = samples[offset] - samples[offset + 7];
            t1 = samples[offset + 1] - samples[offset + 6];
            t2 = samples[offset + 2] - samples[offset + 5];
            t3 = samples[offset + 3] - samples[offset + 4];
            coefficients[offset] = (t10 + t11 - 8 * 128) << pass1Bits;
            coefficients[offset + 4] = (t10 - t11) << pass1Bits;
            int z1 = (t12 + t13) * 4433 + (1 << (constBits - pass1Bits - 1));
            coefficients[offset + 2] = (z1 + t12 * 6270) >> (constBits - pass1Bits);
            coefficients[offset + 6] = (z1 - t13 * 15137) >> (constBits - pass1Bits);
            t12 = t0 + t2;
            t13 = t1 + t3;
            z1 = (t12 + t13) * 9633 + (1 << (constBits - pass1Bits - 1));
            t12 = t12 * -3196 + z1;
            t13 = t13 * -16069 + z1;
            z1 = (t0 + t3) * -7373;
            t0 = t0 * 12299 + z1 + t12;
            t3 = t3 * 2446 + z1 + t13;
            z1 = (t1 + t2) * -20995;
            t1 = t1 * 25172 + z1 + t13;
            t2 = t2 * 16819 + z1 + t12;
            coefficients[offset + 1] = t0 >> (constBits - pass1Bits);
            coefficients[offset + 3] = t1 >> (constBits - pass1Bits);
            coefficients[offset + 5] = t2 >> (constBits - pass1Bits);
            coefficients[offset + 7] = t3 >> (constBits - pass1Bits);
        }
        for (int x = 7; x >= 0; x--) {
            int t0 = coefficients[x] + coefficients[x + 56];
            int t1 = coefficients[x + 8] + coefficients[x + 48];
            int t2 = coefficients[x + 16] + coefficients[x + 40];
            int t3 = coefficients[x + 24] + coefficients[x + 32];
            int t10 = t0 + t3 + (1 << (pass1Bits - 1));
            int t12 = t0 - t3, t11 = t1 + t2, t13 = t1 - t2;
            t0 = coefficients[x] - coefficients[x + 56];
            t1 = coefficients[x + 8] - coefficients[x + 48];
            t2 = coefficients[x + 16] - coefficients[x + 40];
            t3 = coefficients[x + 24] - coefficients[x + 32];
            coefficients[x] = (t10 + t11) >> pass1Bits;
            coefficients[x + 32] = (t10 - t11) >> pass1Bits;
            int z1 = (t12 + t13) * 4433 + (1 << (constBits + pass1Bits - 1));
            coefficients[x + 16] = (z1 + t12 * 6270) >> (constBits + pass1Bits);
            coefficients[x + 48] = (z1 - t13 * 15137) >> (constBits + pass1Bits);
            t12 = t0 + t2;
            t13 = t1 + t3;
            z1 = (t12 + t13) * 9633 + (1 << (constBits - pass1Bits - 1));
            t12 = t12 * -3196 + z1;
            t13 = t13 * -16069 + z1;
            z1 = (t0 + t3) * -7373;
            t0 = t0 * 12299 + z1 + t12;
            t3 = t3 * 2446 + z1 + t13;
            z1 = (t1 + t2) * -20995;
            t1 = t1 * 25172 + z1 + t13;
            t2 = t2 * 16819 + z1 + t12;
            coefficients[x + 8] = t0 >> (constBits + pass1Bits);
            coefficients[x + 24] = t1 >> (constBits + pass1Bits);
            coefficients[x + 40] = t2 >> (constBits + pass1Bits);
            coefficients[x + 56] = t3 >> (constBits + pass1Bits);
        }
    }

    private static final class SourceRowCache {
        private final BufferedImage source;
        private final int sourceWidth;
        private final int[] rowIndexes;
        private final int[][] rows;
        private int nextSlot;

        private SourceRowCache(BufferedImage source, int capacity, int sourceWidth) {
            this.source = source;
            this.sourceWidth = sourceWidth;
            this.rowIndexes = new int[capacity];
            this.rows = new int[capacity][sourceWidth];
            Arrays.fill(rowIndexes, -1);
        }

        private void copyRows(int firstRow, int[][] destination, int rowCount) {
            for (int index = 0; index < rowCount; index++) {
                destination[index] = row(firstRow + index);
            }
        }

        private int[] row(int rowIndex) {
            for (int slot = 0; slot < rowIndexes.length; slot++) {
                if (rowIndexes[slot] == rowIndex) return rows[slot];
            }
            int slot = nextSlot;
            nextSlot = (nextSlot + 1) % rows.length;
            source.getRGB(0, rowIndex, sourceWidth, 1, rows[slot], 0, sourceWidth);
            rowIndexes[slot] = rowIndex;
            return rows[slot];
        }
    }

    private record Kernel(int left, float[] weights) {}
    private record HuffmanCode(int size, int code) {}
    private record Coefficient(int size, int value) {}
}
