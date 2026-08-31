package com.claudecode.session;

/** Optional agent-team identity stamped on ordinary transcript message rows. */
public record TeamInfo(String teamName, String agentName) {

    public static final TeamInfo EMPTY = new TeamInfo(null, null);
}
