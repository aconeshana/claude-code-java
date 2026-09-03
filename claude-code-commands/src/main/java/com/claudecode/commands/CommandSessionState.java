package com.claudecode.commands;

import com.claudecode.commands.context.ContextData;
import com.claudecode.commands.prompt.PromptShellExecutor;
import com.claudecode.commands.session.ResumeRequest;
import com.claudecode.core.engine.CompactProgressEvent;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.Usage;
import com.claudecode.core.pokemon.PokemonProfile;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;


public record CommandSessionState(
    String fallbackModel,
    Supplier<String> modelSupplier,
    Supplier<List<Message>> messagesSupplier,
    Runnable clearMessages,
    Consumer<String> setModel,
    Supplier<Usage> usageSupplier,
    ToDoubleFunction<Usage> costCalculator,
    Supplier<String> workingDirectorySupplier,
    boolean remoteMode,
    Consumer<List<Message>> loadMessages,
    Consumer<List<Message>> loadCompactedMessages,
    Consumer<Message> transcriptRecorder,
    Supplier<String> currentSessionId,
    Function<String, String> sideQuestionRunner,
    Supplier<MessageCompactor> compactService,
    Consumer<String> sessionColorSetter,
    Consumer<PokemonProfile> pokemonSetter,
    Consumer<String> effortValueSetter,
    Supplier<String> effortValueSupplier,
    Function<List<Message>, String> titleGenerator,
    Runnable postCompactCallback,
    Runnable postCompactTranscriptCallback,
    HookDispatcher hookDispatcher,
    Consumer<Message> messageAppender,
    Supplier<String> goalGate,
    Consumer<CompactProgressEvent> onCompactProgress,
    Supplier<Boolean> verboseSupplier,
    Supplier<String> apiBaseUrlSupplier,
    Supplier<List<StatusProperty>> statusRuntimePropertiesSupplier,
    ConfigLiveSetters configLiveSetters,
    Function<String, String> modelValidator,
    Predicate<String> modelAllowed,
    Consumer<ResumeRequest> resumeLauncher,
    Consumer<String> sessionIdSwitcher,
    Runnable resetSessionCost,
    Supplier<ContextData> contextDataCollector,
    Supplier<String> mcpStatusSupplier,
    PromptShellExecutor promptShellExecutor,
    boolean nonInteractive
) {
    /**
     * The session's live working directory. Resolved per call rather than captured, because a
     * Bash {@code cd} or a cross-project resume can move it after this state was assembled.
     */
    public String workingDirectory() {
        return workingDirectorySupplier == null ? null : workingDirectorySupplier.get();
    }

    public String model() {
        if (modelSupplier == null) return fallbackModel;
        try {
            String current = modelSupplier.get();
            return StringUtils.isBlank(current) ? fallbackModel : current;
        } catch (RuntimeException _) {
            return fallbackModel;
        }
    }
}
