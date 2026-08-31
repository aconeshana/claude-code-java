package com.claudecode.cli;

import com.claudecode.core.message.UserMessage;
import com.claudecode.runtime.query.QuerySession;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/** Executes the hidden standalone {@code --rewind-files} startup operation. */
final class CliRewindFilesOperation {

    private CliRewindFilesOperation() {}

    static int run(QuerySession engine, String userMessageId,
                   CliOutput output, CliOutput errorOutput) {
        boolean userMessageExists = engine.conversation().getMessages().stream()
            .anyMatch(message -> message instanceof UserMessage
                && Strings.CS.equals(message.uuid(), userMessageId));
        if (!userMessageExists) {
            errorOutput.println("Error: --rewind-files requires a user message UUID, but "
                + userMessageId + " is not a user message in this session");
            return 1;
        }

        DefaultSdkControlRuntime runtime = new DefaultSdkControlRuntime(
            engine, engine.configuration().getConfig().workingDirectory(),
            null, null, null);
        SdkControlRuntime.RewindFilesResult result = runtime.rewindFiles(userMessageId, false);
        if (!result.canRewind()) {
            errorOutput.println("Error: "
                + StringUtils.defaultIfBlank(result.error(), "Unexpected error"));
            return 1;
        }
        output.println("Files rewound to state at message " + userMessageId);
        return 0;
    }
}
