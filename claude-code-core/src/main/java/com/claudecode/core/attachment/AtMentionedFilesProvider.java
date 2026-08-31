package com.claudecode.core.attachment;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.FileContentAttachment;
import com.claudecode.core.message.ImageFileAttachment;
import com.claudecode.core.imagestore.ImageResizer;


public final class AtMentionedFilesProvider implements AttachmentProvider {

    private static final Pattern MENTION = Pattern.compile("(?:^|\\s)@((?:[^\\s\\\\]|\\\\ )+)");

    @Override
    public String name() {
        return "at_mentioned_files";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        String input = ctx.input();
        if (StringUtils.isBlank(input)) {
            return List.of();
        }
        List<AttachmentPayload> out = new ArrayList<>();
        Matcher m = MENTION.matcher(input);
        while (m.find()) {
            String candidate = m.group(1).replace("\\ ", " ");
            if (!isPlausiblePath(candidate)) {
                continue;
            }
            Path resolved = resolve(candidate, ctx.workingDirectory());
            if (resolved == null || !Files.isRegularFile(resolved)) {
                continue;
            }
            try {
                if (isImagePath(resolved)) {
                    byte[] bytes = Files.readAllBytes(resolved);
                    String ext = extension(resolved);
                    ImageResizer.ResizeResult image =
                        ImageResizer.maybeResizeAndDownsample(bytes, ext);
                    out.add(new ImageFileAttachment(
                        resolved.toString(), candidate,
                        Base64.getEncoder().encodeToString(image.buffer()),
                        image.mediaType(), bytes.length, image.dimensions()));
                } else {
                    out.add(new FileContentAttachment(resolved.toString(),
                        Files.readString(resolved, StandardCharsets.UTF_8)));
                }
            } catch (IOException _) {
                // Unreadable — skip silently.
            }
        }
        return out;
    }

    private static boolean isImagePath(Path path) {
        return switch (extension(path).toLowerCase(Locale.ROOT)) {
            case "png", "jpg", "jpeg", "gif", "webp" -> true;
            default -> false;
        };
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private static boolean isPlausiblePath(String p) {
        if (Strings.CS.startsWith(p, "./") || Strings.CS.startsWith(p, "~/") || Strings.CS.startsWith(p, "/")) return true;
        if (Strings.CS.startsWith(p, "@")) return false;
        if (p.matches("^[#%^&*()].*")) return false;
        return p.matches("^[a-zA-Z0-9._-].*");
    }

    private static Path resolve(String raw, String cwd) {
        try {
            if (Strings.CS.startsWith(raw, "~/")) {
                return Path.of(raw.substring(2)).toAbsolutePath().normalize();
            }
            if (Strings.CS.startsWith(raw, "/")) {
                return Path.of(raw).toAbsolutePath().normalize();
            }
            String stripped = Strings.CS.startsWith(raw, "./") ? raw.substring(2) : raw;
            Path base = cwd != null ? Path.of(cwd) : Path.of(".");
            return base.resolve(stripped).toAbsolutePath().normalize();
        } catch (RuntimeException _) {
            return null;
        }
    }
}
