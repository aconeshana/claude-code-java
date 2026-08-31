package com.claudecode.commands.impl.info;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.config.VersionInfo;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * /version — print the version this session is running.
 */
@SlashCommand(
    name = "version",
    description = "Print the version this session is running"
)
public class VersionCommand implements AnnotatedCommand {

    @Override
    public boolean supportsNonInteractive() { return true; }

    @Override
    public boolean isAvailable(CommandContext context) {
        return Strings.CS.equals("ant", System.getenv("USER_TYPE"));
    }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        String version = readVersion();
        String buildTime = readBuildTime();
        String text = (StringUtils.isNotBlank(buildTime))
            ? version + " (built " + buildTime + ")"
            : version;
        return CommandResult.of(text);
    }


    public static String readVersion() {
        return VersionInfo.version();
    }

    private static String readBuildTime() {
        try (InputStream is = VersionCommand.class.getResourceAsStream(
                "/META-INF/MANIFEST.MF")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                return props.getProperty("Build-Time");
            }
        } catch (IOException _) {}
        return null;
    }
}
