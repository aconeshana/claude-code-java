package com.claudecode.core.message;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.paste.PastedRefParser;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;








@JsonIgnoreProperties(ignoreUnknown = true)
public record PastedContent(
    @JsonProperty("id")          int     id,
    @JsonProperty("type")        String  type,         // "image" | "text"
    @JsonProperty("content")     String  content,      // base64 (image) or raw text
    @JsonProperty("mediaType")   String  mediaType,    // image/png, image/jpeg, ...
    @JsonProperty("filename")    String  filename,
    @JsonProperty("dimensions")  ImageDimensions dimensions,
    @JsonProperty("sourcePath")  String  sourcePath
) implements PastedRefParser.PastedContentLike {

    @JsonCreator
    public PastedContent {
    }

    /** Convenience constructor for image pastes. */
    public static PastedContent image(int id, String base64, String mediaType,
                                      ImageDimensions dims, String sourcePath) {
        return new PastedContent(id, "image", base64, mediaType, null, dims, sourcePath);
    }

    /** Convenience constructor for text pastes. */
    public static PastedContent text(int id, String text) {
        return new PastedContent(id, "text", text, null, null, null, null);
    }

    /**
     * Extracts image blocks from a {@link UserMessage} and rebuilds a pastedContents map keyed by chip
     * id.
     */
    public static Map<Integer, PastedContent> imagesFromMessage(UserMessage msg) {
        if (msg == null || msg.message() == null || msg.message().blocks() == null) {
            return Map.of();
        }
        Map<Integer, PastedContent> result = new LinkedHashMap<>();
        List<Integer> pasteIds = msg.imagePasteIds();
        int imgIdx = 0;
        for (ContentBlock block : msg.message().blocks()) {
            if (block instanceof ImageBlock ib) {
                JsonNode src = ib.source();
                if (src != null && Strings.CS.equals("base64", src.path("type").asText(null))) {
                    String base64 = src.path("data").asText(null);
                    String mediaType = src.path("media_type").asText(null);
                    if (base64 != null) {
                        int id = (pasteIds != null && imgIdx < pasteIds.size())
                            ? pasteIds.get(imgIdx) : imgIdx + 1;
                        result.put(id, image(id, base64, mediaType, null, null));
                    }
                }
                imgIdx++;
            }
        }
        return result;
    }

    public boolean isImage() { return Strings.CS.equals("image", type); }
    public boolean isText()  { return Strings.CS.equals("text", type); }






    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImageDimensions(
        @JsonProperty("originalWidth")  Integer originalWidth,
        @JsonProperty("originalHeight") Integer originalHeight,
        @JsonProperty("displayWidth")   Integer displayWidth,
        @JsonProperty("displayHeight")  Integer displayHeight
    ) {
        @JsonCreator
        public ImageDimensions {
        }
    }
}
