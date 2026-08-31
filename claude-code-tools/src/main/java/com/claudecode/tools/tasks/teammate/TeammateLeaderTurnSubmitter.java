package com.claudecode.tools.tasks.teammate;

/**
 * Submits a teammate→leader message as a new leader turn.
 */
public interface TeammateLeaderTurnSubmitter {

    /**
     * Submit a teammate message (already wrapped as a {@code <teammate-message>} block) as a new leader
     * turn.
     */
    void submitTeammateTurn(String formattedMessage);
}
