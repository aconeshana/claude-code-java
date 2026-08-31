package com.claudecode.tools.tasks;

import org.apache.commons.lang3.StringUtils;
import java.security.SecureRandom;

/**
 * Generates unique task IDs with type prefix + random alphanumeric suffix.
 */
public final class TaskIdGenerator {

    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final int SUFFIX_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TaskIdGenerator() {}

    /**
     * Generates a task ID like "b3k9x2m1" for LOCAL_BASH.
     */
    public static String generate(TaskType type) {
        return generate(type.prefix());
    }


    public static String generate(String prefix) {
        if (StringUtils.isBlank(prefix)
                || !prefix.chars().allMatch(ch -> ch >= 'a' && ch <= 'z')) {
            throw new IllegalArgumentException("Task ID prefix must contain lowercase ASCII letters");
        }
        StringBuilder sb = new StringBuilder(prefix);
        byte[] bytes = new byte[SUFFIX_LENGTH];
        RANDOM.nextBytes(bytes);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            sb.append(ALPHABET.charAt(Byte.toUnsignedInt(bytes[i]) % ALPHABET.length()));
        }
        return sb.toString();
    }

    /**
     * Extracts the task type from a task ID by its prefix.
     */
    public static TaskType extractType(String taskId) {
        if (StringUtils.isEmpty(taskId)) {
            throw new IllegalArgumentException("Task ID cannot be null or empty");
        }
        String prefix = taskId.substring(0, 1);
        for (TaskType type : TaskType.values()) {
            if (type.prefix().equals(prefix)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown task type prefix: " + prefix);
    }
}
