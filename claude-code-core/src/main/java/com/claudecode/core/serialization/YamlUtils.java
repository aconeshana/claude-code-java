package com.claudecode.core.serialization;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

/**
 * Shared YAML parser.
 *
 * <ul>
 *   <li>{@code parseYaml}.</li>
 * </ul>
 */
public final class YamlUtils {
    private static final YAMLMapper MAPPER = new YAMLMapper();

    private YamlUtils() {}

    public static Object parse(String input) throws JsonProcessingException {
        if (StringUtils.isBlank(input)) return null;
        return MAPPER.readValue(input, Object.class);
    }
}
