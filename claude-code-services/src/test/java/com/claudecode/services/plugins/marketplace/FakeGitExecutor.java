package com.claudecode.services.plugins.marketplace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Test double for {@link GitExecutor}: records every invocation and delegates
 * behaviour to an injected handler, so tests can assert command assembly and
 * simulate clone results without any network or git binary.
 */
final class FakeGitExecutor implements GitExecutor {

    final List<List<String>> invocations = new ArrayList<>();
    private final BiFunction<Path, List<String>, GitResult> handler;

    /** Executor whose every command fails (e.g. "not a git repo"). */
    static FakeGitExecutor alwaysFailing() {
        return new FakeGitExecutor((_, _) -> new GitResult(128, "", "fatal: not a git repository"));
    }

    FakeGitExecutor(BiFunction<Path, List<String>, GitResult> handler) {
        this.handler = handler;
    }

    @Override
    public GitResult run(Path cwd, List<String> args) {
        invocations.add(List.copyOf(args));
        return handler.apply(cwd, args);
    }

    List<List<String>> invocationsMatching(String subcommand) {
        return invocations.stream().filter(args -> args.contains(subcommand)).toList();
    }
}
