package com.claudecode.core.config;

import org.apache.commons.lang3.StringUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Single source of truth for the running build's version.
 */
public final class VersionInfo {

    private static final String FALLBACK = "0.1.0-SNAPSHOT";

    private VersionInfo() { }

    public static String version() {
        try (InputStream is = VersionInfo.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                String v = props.getProperty("Implementation-Version");
                if (StringUtils.isNotBlank(v)) return v;
            }
        } catch (IOException _) { }

        return FALLBACK;
    }
}
