package com.claudecode.commands.metadata;

import com.claudecode.commands.XmlConstants;

import com.claudecode.core.text.XmlTagUtils;

/**
 * Encodes slash-command and skill metadata into XML strings that are injected into user-side
 * messages so the model can see which command/skill is executing.
 */
public final class CommandMetadataEncoder {


    private static final String COMMAND_NAME_TAG    = XmlConstants.COMMAND_NAME_TAG;
    private static final String COMMAND_MESSAGE_TAG = XmlConstants.COMMAND_MESSAGE_TAG;
    private static final String COMMAND_ARGS_TAG    = XmlConstants.COMMAND_ARGS_TAG;
    private static final String SKILL_FORMAT_TAG    = XmlConstants.SKILL_FORMAT_TAG;

    private CommandMetadataEncoder() {
    }

    /**
     * Builds the command-input breadcrumb the model sees when a slash command runs.
     */
    public static String encodeCommandInputTags(String commandName, String args) {
        String nameLine    = XmlTagUtils.wrap(COMMAND_NAME_TAG,    "/" + commandName);
        String messageLine = XmlTagUtils.wrap(COMMAND_MESSAGE_TAG, commandName);
        String argsLine    = XmlTagUtils.wrap(COMMAND_ARGS_TAG,    args);
        return nameLine + "\n            " + messageLine + "\n            " + argsLine;
    }













    public static String encodeSlashCommandLoading(String commandName, String args) {
        String messageLine = XmlTagUtils.wrap(COMMAND_MESSAGE_TAG, commandName);
        String nameLine    = XmlTagUtils.wrap(COMMAND_NAME_TAG,    "/" + commandName);
        if (args != null) {
            String argsLine = XmlTagUtils.wrap(COMMAND_ARGS_TAG, args);
            return messageLine + "\n" + nameLine + "\n" + argsLine;
        }
        return messageLine + "\n" + nameLine;
    }












    public static String encodeSkill(String skillName) {
        String messageLine     = XmlTagUtils.wrap(COMMAND_MESSAGE_TAG, skillName);
        String nameLine        = XmlTagUtils.wrap(COMMAND_NAME_TAG,    skillName);
        String skillFormatLine = XmlTagUtils.wrap(SKILL_FORMAT_TAG,    "true");
        return messageLine + "\n" + nameLine + "\n" + skillFormatLine;
    }
}
