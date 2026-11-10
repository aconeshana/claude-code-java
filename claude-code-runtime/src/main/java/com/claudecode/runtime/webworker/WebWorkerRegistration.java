package com.claudecode.runtime.webworker;

import com.claudecode.core.annotation.Explanation;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One interactive process's Session Link endpoint, published for the standalone
 * {@code claude web} supervisor to discover.
 *
 * <p>The token is the same per-process random secret the link server requires
 * for {@code link.hello}. It is written to a directory readable only by the
 * owning user ({@code ~/.claude/web-workers/}, mode 0700), which is the same
 * trust boundary as the socket it guards.
 *
 * @param pid      the owning process; a supervisor treats a dead pid as a stale
 *                 entry, because a crashed process leaves its file behind
 * @param socket   absolute path of the Session Link Unix domain socket
 * @param token    the link authentication token
 * @param workDir  the process's working directory, for the supervisor's listing
 * @param version  the product version, so an older supervisor can refuse loudly
 *                 instead of mis-parsing a newer protocol
 */
@Explanation("Discovery record letting claude web attach to every running TUI process")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebWorkerRegistration(
        long pid,
        String socket,
        String token,
        String workDir,
        String version) {}
