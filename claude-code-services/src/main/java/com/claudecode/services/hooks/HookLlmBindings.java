package com.claudecode.services.hooks;

import com.claudecode.api.LlmClient;
import com.claudecode.services.config.RuntimeSettings;
import com.claudecode.services.model.SideQuery;
import com.claudecode.tools.agent.SubAgentFactory;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;

/**
 * Model-side wiring shared by every LLM-backed hook kind: the side-query
 * client, the live main model, and the sub-agent runtime for
 * {@code type:"agent"} hooks. Shared by reference between a parent dispatcher
 * and its child agent dispatchers so a later {@code /model} change reaches all
 * of them.
 *
 * <ul>
 *   <li>{@code src/utils/hooks/apiQueryHookHelper.ts} — the shared side-query
 *       entry point used by prompt and agent hooks.</li>
 *   <li>{@code src/utils/hooks/execPromptHook.ts} — evaluator model selection
 *       with the released fallback chain.</li>
 * </ul>
 */
public final class HookLlmBindings {

    private static final String LEGACY_EVALUATOR_MODEL = "claude-sonnet-4-20250514";

    private volatile SideQuery sideQuery;
    private volatile String model;
    private volatile Supplier<String> modelSupplier = () -> model;
    private volatile SubAgentFactory agentHookFactory;

    /** Constructs a {@link SideQuery} around a raw client; prefer {@link #bindSideQuery}. */
    public void bindClient(LlmClient llmClient) {
        this.sideQuery = llmClient != null ? new SideQuery(llmClient) : null;
    }

    /**
     * Preferred injection point — the shared {@link SideQuery} so hooks share the same
     * side-query pipeline as rename / permission explainer.
     */
    public void bindSideQuery(SideQuery sideQuery) {
        this.sideQuery = sideQuery;
    }

    /** Default LLM model for hook execution when no live supplier is bound. */
    public void setModel(String model) {
        this.model = model;
    }

    /** Live model source so /model changes also affect later prompt hooks. */
    public void bindModelSupplier(Supplier<String> supplier) {
        this.modelSupplier = supplier != null ? supplier : () -> model;
    }

    /** Installs the real sub-agent runtime used by {@code type:"agent"} hooks. */
    public void bindAgentFactory(SubAgentFactory factory) {
        this.agentHookFactory = factory;
    }

    SideQuery sideQuery() {
        return sideQuery;
    }

    SubAgentFactory agentFactory() {
        return agentHookFactory;
    }

    String currentModel() {
        try {
            String live = modelSupplier.get();
            return StringUtils.isNotBlank(live) ? live : model;
        } catch (RuntimeException _) {
            return model;
        }
    }

    /**
     * Hook-evaluator model with the released fallback chain. 236 pins both the
     * stop-condition and prompt-hook evaluators to the live main model (with the
     * legacy constant when none is known); the {@code hookEvaluatorModel}
     * settings key is a Java-side override that takes precedence when non-blank.
     */
    String evaluatorModel() {
        String override = RuntimeSettings.loadMainModelScenarioModel("hookEvaluatorModel");
        if (override != null) return override;
        String current = currentModel();
        return current != null ? current : LEGACY_EVALUATOR_MODEL;
    }

    /** Fallback model for the LLM-only agent-hook path, which never consults the live supplier. */
    String agentFallbackModel() {
        return model != null ? model : LEGACY_EVALUATOR_MODEL;
    }
}
