package com.claudecode.cli;

import com.claudecode.session.TeamInfo;
import com.claudecode.tools.tasks.TeamRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliSessionLifecycleBootstrapTeamInfoTest {

    @Test
    void resolvesLeadIdentityFromTheActiveTeamRegistry() {
        String sessionId = UUID.randomUUID().toString();
        String teamName = "team-" + UUID.randomUUID();
        TeamRegistry.instance().create(
            teamName, "", "team-lead@" + teamName, sessionId);
        try {
            assertEquals(new TeamInfo(teamName, "team-lead"),
                CliSessionLifecycleBootstrap.resolveTeamInfo(sessionId));
        } finally {
            TeamRegistry.instance().remove(teamName);
        }
    }
}
