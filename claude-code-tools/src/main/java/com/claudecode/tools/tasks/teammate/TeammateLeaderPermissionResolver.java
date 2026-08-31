package com.claudecode.tools.tasks.teammate;

import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.tools.tasks.InProcessTeammateTask;

/**
 * Resolves a teammate's permission / plan ask on behalf of the leader.
 */
public interface TeammateLeaderPermissionResolver {

    /** Resolve a teammate's permission ask (tool requiring ASK-level approval). */
    PermissionAskCallback.Result resolvePermission(PermissionAskContext ctx);

    /**
     * Resolve a teammate's plan-approval ask. Defaults to approved; the composition root may
     * override to surface a real plan-approval dialog. Returns a {@link
     * com.claudecode.tools.tasks.InProcessTeammateTask.PlanApproval} (matches the wire shape
     * the teammate decodes), not a permission {@code Result}.
     */
    default InProcessTeammateTask.PlanApproval resolvePlanApproval(String summary) {
        // Headless fallback: approve and inherit the leader's default mode.

        return new InProcessTeammateTask.PlanApproval(true, "", "default");
    }
}
