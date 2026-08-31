package com.claudecode.tools.workflows;

import com.claudecode.tools.bundled.BundledResourceCatalog;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads verbatim built-in workflow scripts from the currently bound bundled release.
 */
public final class BundledWorkflowLoader {

    private BundledWorkflowLoader() {}

    public static List<WorkflowDefinition> load() {
        return load(BundledResourceCatalog.current());
    }

    static List<WorkflowDefinition> load(BundledResourceCatalog resources) {
        List<WorkflowDefinition> definitions = new ArrayList<>();
        for (BundledResourceCatalog.WorkflowResource resource : resources.workflows()) {
            String script = resources.readText(resource.path()).stripTrailing();
            ParsedWorkflowScript parsed = WorkflowScriptParser.parse(script);
            definitions.add(new WorkflowDefinition(parsed.metadata(), script,
                parsed.body(), WorkflowSource.BUILT_IN, null, null,
                resource.hidden(), false));
        }
        return List.copyOf(definitions);
    }
}
