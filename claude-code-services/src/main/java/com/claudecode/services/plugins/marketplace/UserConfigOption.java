package com.claudecode.services.plugins.marketplace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One user-configurable option declared in a plugin manifest's {@code userConfig} map.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserConfigOption(
    String type,
    String title,
    String description,
    Boolean required,
    @JsonProperty("default") JsonNode defaultValue,
    Boolean multiple,
    Boolean sensitive,
    Double min,
    Double max) {}
