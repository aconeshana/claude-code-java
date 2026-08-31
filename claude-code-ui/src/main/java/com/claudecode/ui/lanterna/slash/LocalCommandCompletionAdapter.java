package com.claudecode.ui.lanterna.slash;

import com.claudecode.commands.CommandOutputChannel;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.CommandResultDisplay;
import com.claudecode.commands.metadata.CommandMetadataEncoder;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * Maps the command-layer completion contract to transcript messages.
 */
final class LocalCommandCompletionAdapter {

    private LocalCommandCompletionAdapter() { }

    static List<SDKMessage> toMessages(String commandName, String args, CommandResult result) {
        if (result == null || result.display() == CommandResultDisplay.SKIP) {
            return List.of();
        }

        List<SDKMessage> messages = new ArrayList<>();
        if (result.outputChannel() != CommandOutputChannel.NONE) {
            if (result.display() == CommandResultDisplay.USER
                    || result.display() == CommandResultDisplay.LOCAL) {
                String input = "/" + commandName
                    + (StringUtils.isBlank(args) ? "" : " " + args);
                messages.add(new SDKMessage.User(MessageFactory.createUserMessage(input)));
                if (!result.output().isEmpty()) {
                    String taggedOutput = result.outputChannel().wrap(result.output());
                    if (result.display() == CommandResultDisplay.USER) {
                        messages.add(new SDKMessage.User(MessageFactory.createUserMessage(taggedOutput)));
                    } else {
                        messages.add(new SDKMessage.System(new SystemMessage(
                            UUID.randomUUID().toString(), "local_command", "info", taggedOutput)));
                    }
                }
            } else if (!result.output().isEmpty()) {
                String taggedOutput = result.outputChannel().wrap(result.output());
                String echo = CommandMetadataEncoder.encodeCommandInputTags(commandName, args);
                messages.add(new SDKMessage.System(new SystemMessage(
                    UUID.randomUUID().toString(), "local_command", "info", echo)));
                messages.add(new SDKMessage.System(new SystemMessage(
                    UUID.randomUUID().toString(), "local_command", "info", taggedOutput)));
            }
        }

        for (String meta : result.metaMessages()) {
            messages.add(new SDKMessage.User(MessageFactory.createUserMessage(meta, true)));
        }
        return List.copyOf(messages);
    }

}
