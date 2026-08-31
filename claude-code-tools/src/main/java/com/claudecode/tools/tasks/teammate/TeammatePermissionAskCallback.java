package com.claudecode.tools.tasks.teammate;

import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.tools.tasks.InProcessTeammateTask;

import java.util.function.Supplier;

/**
 * Permission-ask callback installed on a teammate's sub-agent context.
 */
public final class TeammatePermissionAskCallback implements PermissionAskCallback {

    private final Supplier<InProcessTeammateTask> taskSupplier;

    public TeammatePermissionAskCallback(Supplier<InProcessTeammateTask> taskSupplier) {
        this.taskSupplier = taskSupplier;
    }

    @Override
    public Result ask(PermissionAskContext ctx) {
        return taskSupplier.get().requestPermission(ctx);
    }
}
