package com.claudecode.commands.policy;

/**
 * Pure command policy deciding whether inference-configuration commands may execute while a query
 * is running.
 */
public final class ImmediateCommandStrategy {

    static final String USER_TYPE_ANT = "ant";
    static final String ENV_USER_TYPE = "USER_TYPE";

    private ImmediateCommandStrategy() { }

    public static boolean inferenceConfigCommandImmediate(EnvResolver envResolver) {
        return USER_TYPE_ANT.equals(envResolver.getEnv(ENV_USER_TYPE));
    }

    public static boolean inferenceConfigCommandImmediate() {
        return inferenceConfigCommandImmediate(System::getenv);
    }

    @FunctionalInterface
    public interface EnvResolver {
        String getEnv(String name);
    }
}
