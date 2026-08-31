package com.claudecode.core.paste;

import com.claudecode.core.imagestore.ImageResizer;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.platform.Platform;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Base64;

/**
 * In-process macOS clipboard image reader.
 */
final class MacNativeClipboardImageReader {

    private static final String APP_KIT =
        "/System/Library/Frameworks/AppKit.framework/AppKit";
    private static final String CORE_GRAPHICS =
        "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics";

    private MacNativeClipboardImageReader() {}

    static ClipboardReadResult read() {
        if (Platform.CURRENT != Platform.DARWIN) {
            return ClipboardReadResult.Unavailable.permanent(null);
        }
        try {
            NativeClipboardData nativeImage = NativeCalls.readImage(
                ImageResizer.IMAGE_MAX_WIDTH, ImageResizer.IMAGE_MAX_HEIGHT);
            if (nativeImage == null) return new ClipboardReadResult.Empty();
            return new ClipboardReadResult.Image(prepareNativeImage(nativeImage));
        } catch (LinkageError failure) {
            return ClipboardReadResult.Unavailable.permanent(failure);
        } catch (Throwable failure) {
            return ClipboardReadResult.Unavailable.transientFailure(failure);
        }
    }

    static NativeClipboardData resizePng(byte[] png, int maxWidth, int maxHeight) {
        if (Platform.CURRENT != Platform.DARWIN) {
            throw new UnsupportedOperationException("native PNG resize requires macOS");
        }
        try {
            return NativeCalls.resizePng(png, maxWidth, maxHeight);
        } catch (Throwable failure) {
            throw new IllegalStateException("native PNG resize failed", failure);
        }
    }

    static ImagePaste.ImageWithDimensions prepareNativeImage(NativeClipboardData nativeImage) {
        byte[] output = nativeImage.png();
        String mediaType = "image/png";
        int displayWidth = nativeImage.displayWidth();
        int displayHeight = nativeImage.displayHeight();
        if (output.length > ImageResizer.IMAGE_TARGET_RAW_SIZE) {
            ImageResizer.ResizeResult resized =
                ImageResizer.maybeResizeAndDownsample(output, "png");
            output = resized.buffer();
            mediaType = resized.mediaType();
            if (resized.dimensions() != null) {
                displayWidth = resized.dimensions().displayWidth();
                displayHeight = resized.dimensions().displayHeight();
        }
        }
        PastedContent.ImageDimensions dimensions = new PastedContent.ImageDimensions(
            nativeImage.originalWidth(), nativeImage.originalHeight(),
            displayWidth, displayHeight);
        return new ImagePaste.ImageWithDimensions(
            Base64.getEncoder().encodeToString(output), mediaType, dimensions);
    }

    record NativeClipboardData(
        byte[] png,
        int originalWidth,
        int originalHeight,
        int displayWidth,
        int displayHeight
    ) {
        NativeClipboardData {
            if (png == null || png.length == 0) {
                throw new IllegalArgumentException("native clipboard PNG must not be empty");
            }
            if (originalWidth <= 0 || originalHeight <= 0
                    || displayWidth <= 0 || displayHeight <= 0) {
                throw new IllegalArgumentException("native clipboard dimensions must be positive");
            }
            if (displayWidth > ImageResizer.IMAGE_MAX_WIDTH
                    || displayHeight > ImageResizer.IMAGE_MAX_HEIGHT) {
                throw new IllegalArgumentException("native clipboard image exceeds display limits");
            }
        }
    }

    private static final class NativeCalls {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final MemoryLayout POINTER =
            LINKER.canonicalLayouts().get("void*");
        private static final MemoryLayout SIZE_T =
            LINKER.canonicalLayouts().get("size_t");
        private static final MemoryLayout CG_RECT = MemoryLayout.structLayout(
            ValueLayout.JAVA_DOUBLE.withName("x"),
            ValueLayout.JAVA_DOUBLE.withName("y"),
            ValueLayout.JAVA_DOUBLE.withName("width"),
            ValueLayout.JAVA_DOUBLE.withName("height"));
        private static final SymbolLookup OBJC = LINKER.defaultLookup();
        private static final SymbolLookup APPKIT =
            SymbolLookup.libraryLookup(APP_KIT, Arena.global());
        private static final SymbolLookup COREGRAPHICS =
            SymbolLookup.libraryLookup(CORE_GRAPHICS, Arena.global());

        private static final MethodHandle OBJC_GET_CLASS = downcall(
            OBJC, "objc_getClass", FunctionDescriptor.of(POINTER, POINTER));
        private static final MethodHandle SEL_REGISTER_NAME = downcall(
            OBJC, "sel_registerName", FunctionDescriptor.of(POINTER, POINTER));
        private static final MethodHandle MSG_SEND = downcall(
            OBJC, "objc_msgSend", FunctionDescriptor.of(POINTER, POINTER, POINTER));
        private static final MethodHandle MSG_SEND_VOID = downcall(
            OBJC, "objc_msgSend", FunctionDescriptor.ofVoid(POINTER, POINTER));
        private static final MethodHandle MSG_SEND_POINTER_ARG = downcall(
            OBJC, "objc_msgSend", FunctionDescriptor.of(POINTER, POINTER, POINTER, POINTER));
        private static final MethodHandle MSG_SEND_POINTER_SIZE_ARGS = downcall(
            OBJC, "objc_msgSend",
            FunctionDescriptor.of(POINTER, POINTER, POINTER, POINTER, SIZE_T));
        private static final MethodHandle MSG_SEND_SIZE = downcall(
            OBJC, "objc_msgSend", FunctionDescriptor.of(SIZE_T, POINTER, POINTER));
        private static final MethodHandle MSG_SEND_LONG_POINTER_ARG = downcall(
            OBJC, "objc_msgSend",
            FunctionDescriptor.of(POINTER, POINTER, POINTER,
                ValueLayout.JAVA_LONG, POINTER));
        private static final MethodHandle AUTORELEASE_PUSH = downcall(
            OBJC, "objc_autoreleasePoolPush", FunctionDescriptor.of(POINTER));
        private static final MethodHandle AUTORELEASE_POP = downcall(
            OBJC, "objc_autoreleasePoolPop", FunctionDescriptor.ofVoid(POINTER));

        private static final MethodHandle CG_IMAGE_GET_WIDTH = downcall(
            COREGRAPHICS, "CGImageGetWidth", FunctionDescriptor.of(SIZE_T, POINTER));
        private static final MethodHandle CG_IMAGE_GET_HEIGHT = downcall(
            COREGRAPHICS, "CGImageGetHeight", FunctionDescriptor.of(SIZE_T, POINTER));
        private static final MethodHandle CG_COLOR_SPACE_CREATE_DEVICE_RGB = downcall(
            COREGRAPHICS, "CGColorSpaceCreateDeviceRGB", FunctionDescriptor.of(POINTER));
        private static final MethodHandle CG_COLOR_SPACE_RELEASE = downcall(
            COREGRAPHICS, "CGColorSpaceRelease", FunctionDescriptor.ofVoid(POINTER));
        private static final MethodHandle CG_BITMAP_CONTEXT_CREATE = downcall(
            COREGRAPHICS, "CGBitmapContextCreate",
            FunctionDescriptor.of(POINTER, POINTER, SIZE_T, SIZE_T, SIZE_T, SIZE_T,
                POINTER, ValueLayout.JAVA_INT));
        private static final MethodHandle CG_CONTEXT_SET_INTERPOLATION_QUALITY = downcall(
            COREGRAPHICS, "CGContextSetInterpolationQuality",
            FunctionDescriptor.ofVoid(POINTER, ValueLayout.JAVA_INT));
        private static final MethodHandle CG_CONTEXT_DRAW_IMAGE = downcall(
            COREGRAPHICS, "CGContextDrawImage",
            FunctionDescriptor.ofVoid(POINTER, CG_RECT, POINTER));
        private static final MethodHandle CG_BITMAP_CONTEXT_CREATE_IMAGE = downcall(
            COREGRAPHICS, "CGBitmapContextCreateImage",
            FunctionDescriptor.of(POINTER, POINTER));
        private static final MethodHandle CG_CONTEXT_RELEASE = downcall(
            COREGRAPHICS, "CGContextRelease", FunctionDescriptor.ofVoid(POINTER));
        private static final MethodHandle CG_IMAGE_RELEASE = downcall(
            COREGRAPHICS, "CGImageRelease", FunctionDescriptor.ofVoid(POINTER));

        private NativeCalls() {}

        private static NativeClipboardData readImage(int maxWidth, int maxHeight) throws Throwable {
            MemorySegment pool = (MemorySegment) AUTORELEASE_PUSH.invokeExact();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment pasteboardClass = objcClass(arena, "NSPasteboard");
                MemorySegment pasteboard = send(
                    pasteboardClass, selector(arena, "generalPasteboard"));
                if (isNull(pasteboard)) return null;

                MemorySegment stringClass = objcClass(arena, "NSString");
                NativeClipboardData png = readDataForType(
                    arena, pasteboard, stringClass, "public.png", true, maxWidth, maxHeight);
                if (png != null) return png;
                NativeClipboardData jpeg = readDataForType(
                    arena, pasteboard, stringClass, "public.jpeg", false, maxWidth, maxHeight);
                if (jpeg != null) return jpeg;
                return readGenericPasteboardImage(arena, pasteboard, maxWidth, maxHeight);
            } finally {
                AUTORELEASE_POP.invokeExact(pool);
                }
        }

        private static NativeClipboardData resizePng(
            byte[] png,
            int maxWidth,
            int maxHeight
        ) throws Throwable {
            MemorySegment pool = (MemorySegment) AUTORELEASE_PUSH.invokeExact();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment input = arena.allocate(png.length, 1);
                input.copyFrom(MemorySegment.ofArray(png));
                MemorySegment dataClass = objcClass(arena, "NSData");
                MemorySegment data = (MemorySegment) MSG_SEND_POINTER_SIZE_ARGS.invokeExact(
                    dataClass, selector(arena, "dataWithBytes:length:"), input, (long) png.length);
                if (isNull(data)) throw new IllegalStateException("unable to create PNG data");
                MemorySegment bitmapClass = objcClass(arena, "NSBitmapImageRep");
                MemorySegment bitmap = sendPointerArgument(
                    bitmapClass, selector(arena, "imageRepWithData:"), data);
                if (isNull(bitmap)) throw new IllegalArgumentException("invalid PNG data");
                int originalWidth = checkedDimension(bitmap, arena, "pixelsWide");
                int originalHeight = checkedDimension(bitmap, arena, "pixelsHigh");
                int[] target = constrainedDimensions(
                    originalWidth, originalHeight, maxWidth, maxHeight);
                if (target[0] == originalWidth && target[1] == originalHeight) {
                    return new NativeClipboardData(
                        png, originalWidth, originalHeight, originalWidth, originalHeight);
                }
                MemorySegment image = send(bitmap, selector(arena, "CGImage"));
                if (isNull(image)) throw new IllegalStateException("unable to decode PNG CGImage");
                byte[] output = encodePng(
                    arena, image, originalWidth, originalHeight, target[0], target[1]);
                return new NativeClipboardData(
                    output, originalWidth, originalHeight, target[0], target[1]);
            } finally {
                AUTORELEASE_POP.invokeExact(pool);
            }
        }

        private static NativeClipboardData readDataForType(
            Arena arena,
            MemorySegment pasteboard,
            MemorySegment stringClass,
            String type,
            boolean preservePng,
            int maxWidth,
            int maxHeight
        ) throws Throwable {
            MemorySegment nativeType = sendPointerArgument(
                stringClass,
                selector(arena, "stringWithUTF8String:"),
                arena.allocateFrom(type));
            MemorySegment data = sendPointerArgument(
                pasteboard,
                selector(arena, "dataForType:"),
                nativeType);
            if (isNull(data)) return null;

            MemorySegment bitmapClass = objcClass(arena, "NSBitmapImageRep");
            MemorySegment bitmap = sendPointerArgument(
                bitmapClass, selector(arena, "imageRepWithData:"), data);
            if (isNull(bitmap)) return null;
            int originalWidth = checkedDimension(bitmap, arena, "pixelsWide");
            int originalHeight = checkedDimension(bitmap, arena, "pixelsHigh");
            int[] target = constrainedDimensions(
                originalWidth, originalHeight, maxWidth, maxHeight);
            if (preservePng && target[0] == originalWidth && target[1] == originalHeight) {
                return new NativeClipboardData(
                    copyData(data, arena), originalWidth, originalHeight,
                    originalWidth, originalHeight);
            }
            MemorySegment image = send(bitmap, selector(arena, "CGImage"));
            if (isNull(image)) return null;
            byte[] output = encodePng(
                arena, image, originalWidth, originalHeight, target[0], target[1]);
            return new NativeClipboardData(
                output, originalWidth, originalHeight, target[0], target[1]);
        }

        private static NativeClipboardData readGenericPasteboardImage(
            Arena arena,
            MemorySegment pasteboard,
            int maxWidth,
            int maxHeight
        ) throws Throwable {
            MemorySegment imageClass = objcClass(arena, "NSImage");
            MemorySegment image = send(imageClass, selector(arena, "alloc"));
            image = sendPointerArgument(
                image,
                selector(arena, "initWithPasteboard:"),
                pasteboard);
            if (isNull(image)) return null;
            try {
                MemorySegment tiff = send(image, selector(arena, "TIFFRepresentation"));
                if (isNull(tiff)) return null;
                MemorySegment bitmapClass = objcClass(arena, "NSBitmapImageRep");
                MemorySegment bitmap = sendPointerArgument(
                    bitmapClass, selector(arena, "imageRepWithData:"), tiff);
                if (isNull(bitmap)) return null;
                int originalWidth = checkedDimension(bitmap, arena, "pixelsWide");
                int originalHeight = checkedDimension(bitmap, arena, "pixelsHigh");
                int[] target = constrainedDimensions(
                    originalWidth, originalHeight, maxWidth, maxHeight);
                MemorySegment source = send(bitmap, selector(arena, "CGImage"));
                if (isNull(source)) return null;
                byte[] output = encodePng(
                    arena, source, originalWidth, originalHeight, target[0], target[1]);
                return new NativeClipboardData(
                    output, originalWidth, originalHeight, target[0], target[1]);
            } finally {
                MSG_SEND_VOID.invokeExact(image, selector(arena, "release"));
            }
        }

        private static byte[] encodePng(
            Arena arena,
            MemorySegment source,
            int sourceWidth,
            int sourceHeight,
            int targetWidth,
            int targetHeight
        ) throws Throwable {
            MemorySegment outputImage = source;
            boolean releaseOutputImage = false;
            if (sourceWidth != targetWidth || sourceHeight != targetHeight) {
                outputImage = resizeImage(arena, source, targetWidth, targetHeight);
                releaseOutputImage = true;
            }
            try {
                MemorySegment bitmapClass = objcClass(arena, "NSBitmapImageRep");
                MemorySegment bitmap = send(bitmapClass, selector(arena, "alloc"));
                bitmap = sendPointerArgument(
                    bitmap, selector(arena, "initWithCGImage:"), outputImage);
                if (isNull(bitmap)) throw new IllegalStateException("unable to create PNG image rep");
                try {
                MemorySegment png = (MemorySegment) MSG_SEND_LONG_POINTER_ARG.invokeExact(
                    bitmap,
                    selector(arena, "representationUsingType:properties:"),
                    4L,
                    MemorySegment.NULL);
                    if (isNull(png)) throw new IllegalStateException("unable to encode clipboard PNG");
                    return copyData(png, arena);
            } finally {
                    MSG_SEND_VOID.invokeExact(bitmap, selector(arena, "release"));
            }
            } finally {
                if (releaseOutputImage) CG_IMAGE_RELEASE.invokeExact(outputImage);
            }
        }

        private static MemorySegment resizeImage(
            Arena arena,
            MemorySegment source,
            int width,
            int height
        ) throws Throwable {
            long sourceWidth = (long) CG_IMAGE_GET_WIDTH.invokeExact(source);
            long sourceHeight = (long) CG_IMAGE_GET_HEIGHT.invokeExact(source);
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                throw new IllegalStateException("clipboard CGImage has invalid dimensions");
            }
            MemorySegment colorSpace =
                (MemorySegment) CG_COLOR_SPACE_CREATE_DEVICE_RGB.invokeExact();
            if (isNull(colorSpace)) throw new IllegalStateException("unable to create RGB color space");
            try {
                long bytesPerRow = Math.multiplyExact((long) width, 4L);
                MemorySegment context = (MemorySegment) CG_BITMAP_CONTEXT_CREATE.invokeExact(
                    MemorySegment.NULL, (long) width, (long) height, 8L, bytesPerRow,
                    colorSpace, 16_385);
                if (isNull(context)) throw new IllegalStateException("unable to create bitmap context");
                try {
                    CG_CONTEXT_SET_INTERPOLATION_QUALITY.invokeExact(context, 3);
                    MemorySegment rect = arena.allocate(CG_RECT);
                    rect.set(ValueLayout.JAVA_DOUBLE, 0, 0.0d);
                    rect.set(ValueLayout.JAVA_DOUBLE, 8, 0.0d);
                    rect.set(ValueLayout.JAVA_DOUBLE, 16, (double) width);
                    rect.set(ValueLayout.JAVA_DOUBLE, 24, (double) height);
                    CG_CONTEXT_DRAW_IMAGE.invokeExact(context, rect, source);
                    MemorySegment image =
                        (MemorySegment) CG_BITMAP_CONTEXT_CREATE_IMAGE.invokeExact(context);
                    if (isNull(image)) throw new IllegalStateException("unable to create resized CGImage");
                    return image;
                } finally {
                    CG_CONTEXT_RELEASE.invokeExact(context);
                }
            } finally {
                CG_COLOR_SPACE_RELEASE.invokeExact(colorSpace);
            }
        }

        private static int checkedDimension(
            MemorySegment object,
            Arena arena,
            String selectorName
        ) throws Throwable {
            long value = (long) MSG_SEND_SIZE.invokeExact(object, selector(arena, selectorName));
            if (value <= 0 || value > Integer.MAX_VALUE) {
                throw new IllegalStateException("invalid clipboard image dimension");
            }
            return (int) value;
        }

        private static int[] constrainedDimensions(
            int originalWidth,
            int originalHeight,
            int maxWidth,
            int maxHeight
        ) {
            int width = originalWidth;
            int height = originalHeight;
            if (width > maxWidth) {
                height = (int) Math.round((double) height * maxWidth / width);
                width = maxWidth;
            }
            if (height > maxHeight) {
                width = (int) Math.round((double) width * maxHeight / height);
                height = maxHeight;
            }
            return new int[]{Math.max(1, width), Math.max(1, height)};
        }

        private static byte[] copyData(MemorySegment data, Arena arena) throws Throwable {
            long length = (long) MSG_SEND_SIZE.invokeExact(data, selector(arena, "length"));
            if (length <= 0 || length > Integer.MAX_VALUE) {
                throw new IllegalStateException("clipboard image data is empty or too large");
            }
            MemorySegment bytes = send(data, selector(arena, "bytes"));
            if (isNull(bytes)) throw new IllegalStateException("clipboard image has no bytes");
            return bytes.reinterpret(length).toArray(ValueLayout.JAVA_BYTE);
        }

        private static MemorySegment objcClass(Arena arena, String name) throws Throwable {
            return (MemorySegment) OBJC_GET_CLASS.invokeExact(arena.allocateFrom(name));
        }

        private static MemorySegment selector(Arena arena, String name) throws Throwable {
            return (MemorySegment) SEL_REGISTER_NAME.invokeExact(arena.allocateFrom(name));
        }

        private static MemorySegment send(
            MemorySegment receiver,
            MemorySegment selector
        ) throws Throwable {
            return (MemorySegment) MSG_SEND.invokeExact(receiver, selector);
        }

        private static MemorySegment sendPointerArgument(
            MemorySegment receiver,
            MemorySegment selector,
            MemorySegment argument
        ) throws Throwable {
            return (MemorySegment) MSG_SEND_POINTER_ARG.invokeExact(
                receiver, selector, argument);
        }

        private static boolean isNull(MemorySegment segment) {
            return segment == null || segment.address() == 0;
        }

        private static MethodHandle downcall(
            SymbolLookup lookup,
            String name,
            FunctionDescriptor descriptor
        ) {
            MemorySegment symbol = lookup.find(name)
                .or(() -> APPKIT.find(name))
                .or(() -> COREGRAPHICS.find(name))
                .orElseThrow(() -> new IllegalStateException("missing native symbol: " + name));
            return LINKER.downcallHandle(symbol, descriptor);
        }
    }
}
