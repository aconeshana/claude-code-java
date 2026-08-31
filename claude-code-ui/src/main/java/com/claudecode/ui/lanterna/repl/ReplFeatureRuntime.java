package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.engine.PermissionExplainerCallback;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.loop.LoopWakeupManager;
import com.claudecode.tools.skills.InvokedSkillRegistry;
import com.claudecode.tools.skills.Skill;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.workflows.WorkflowRunStore;
import com.claudecode.tools.worktree.WorktreeSession;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shared feature instances for one interactive REPL session.
 */
public record ReplFeatureRuntime(
    PermissionGate permissionGate,
    ToolRegistry toolRegistry,
    TaskRegistry taskRegistry,
    WorkflowRunStore workflowRuns,
    InvokedSkillRegistry invokedSkills,
    LoopWakeupManager loopWakeups,
    Supplier<List<Skill>> skills,
    Consumer<String> skillHookRegistrar,
    PermissionExplainerCallback permissionExplainer,
    Supplier<WorktreeSession> currentWorktree
) {}
