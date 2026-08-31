package com.claudecode.services.plugins.marketplace;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.net.URI;

import com.claudecode.core.process.SubprocessEnvironment;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens plugin homepage/repository URLs with the platform default browser.
 */
final class ExternalUrlOpener {

    @FunctionalInterface
    interface CommandRunner {
        int run(List<String> command) throws Exception;
    }

    private ExternalUrlOpener() {}

    static boolean open(String url) {
        return open(url, System.getProperty("os.name", ""),
            SubprocessEnvironment.get("BROWSER"),
            command -> new ProcessBuilder(command).start().waitFor());
    }

    static boolean open(String url, String osName, String browser, CommandRunner runner) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!Strings.CI.equals("http", scheme) && !Strings.CI.equals("https", scheme)) {
                return false;
            }
            String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
            List<String> command = new ArrayList<>();
            if (StringUtils.isNotBlank(browser)) {
                command.add(browser);
                command.add(Strings.CS.contains(os, "win") ? "\"" + url + "\"" : url);
            } else if (Strings.CS.contains(os, "win")) {
                command.add("rundll32");
                command.add("url,OpenURL");
                command.add(url);
            } else {
                command.add(Strings.CS.contains(os, "mac") ? "open" : "xdg-open");
                command.add(url);
            }
            return runner.run(List.copyOf(command)) == 0;
        } catch (Exception _) {
            return false;
        }
    }
}
