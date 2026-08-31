package com.claudecode.tools.tasks.teammate;

import com.claudecode.core.engine.AbortController;
import com.claudecode.permissions.PermissionMode;

import java.util.concurrent.atomic.AtomicReference;


public final class TeammateContext {

    /** The teammate's own agent id (its task id). */
    private final String agentId;
    /** Team this teammate belongs to (null for a standalone spawn). */
    private final String teamId;
/**
     * Permission mode the teammate runs under.
     */
    private final AtomicReference<PermissionMode> permissionMode;
    /** Whether the teammate is required to obtain plan approval before coding. */
    private final AtomicReference<Boolean> planModeRequired;
/** Shared abort handle — {@link com.claudecode.tools.tasks.InProcessTeammateTask#stop}
     * calls {@code abort("shutdown")} on this to unwind the running query loop. */
    private final AbortController abortController;
/**
     * Optional human-readable agent name (e.g.
     */
    private final String name;

    private TeammateContext(String agentId, String teamId, AtomicReference<PermissionMode> permissionMode,
                            boolean planMode, AbortController abortController, String name) {
        this.agentId = agentId;
        this.teamId = teamId;
        this.permissionMode = permissionMode;
        this.planModeRequired = new AtomicReference<>(planMode);
        this.abortController = abortController;
        this.name = name;
    }

    public String agentId() { return agentId; }
    public String teamId() { return teamId; }
    public PermissionMode permissionMode() { return permissionMode.get(); }
    public boolean planMode() { return planModeRequired.get(); }
    public AbortController abortController() { return abortController; }
    public String name() { return name; }

/**
     * Updates the teammate's permission mode.
     */
    public void setPermissionMode(PermissionMode mode) {
        if (mode != null) permissionMode.set(mode);
    }




    public void setPlanModeRequired(boolean required) {
        planModeRequired.set(required);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String agentId;
        private String teamId;
        private PermissionMode permissionMode = PermissionMode.DEFAULT;
        private boolean planMode = false;
        private AbortController abortController;
        private String name;

        public Builder agentId(String v) { this.agentId = v; return this; }
        public Builder teamId(String v) { this.teamId = v; return this; }
        public Builder permissionMode(PermissionMode v) { this.permissionMode = v; return this; }
        public Builder planMode(boolean v) { this.planMode = v; return this; }
        public Builder abortController(AbortController v) { this.abortController = v; return this; }
        public Builder name(String v) { this.name = v; return this; }

        public TeammateContext build() {
            return new TeammateContext(agentId, teamId, new AtomicReference<>(permissionMode), planMode, abortController, name);
        }
    }
}
