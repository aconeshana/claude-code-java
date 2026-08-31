package com.claudecode.runtime.query;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.SubmitOptions;

import com.claudecode.core.message.Usage;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.model.PermissionModeKind;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;


class OpusplanRuntimeModelTest {

    /** Captures every request's model; replies with a trivial text turn. */
    private record CapturingClient(List<String> models) implements StreamingClient {
        @Override
        public Iterator<StreamingEvent> createStream(StreamRequest request) {
            models.add(request.model());
            return List.<StreamingEvent>of(
                new StreamingEvent.MessageStartEvent("m1", request.model(), List.of(), Usage.EMPTY),
                new StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "ok"),
                new StreamingEvent.MessageStopEvent()
            ).iterator();
        }
        @Override
        public String getModel() { return "capture"; }
    }

    private static void drain(DefaultQuerySession engine, String prompt) {
        Iterator<?> it = engine.submitMessage(prompt, SubmitOptions.DEFAULT);
        while (it.hasNext()) it.next();
    }

    @Test
    void planModeSwapsToOpusAndBack() {
        List<String> models = new CopyOnWriteArrayList<>();
        AtomicReference<PermissionModeKind> mode = new AtomicReference<>(PermissionModeKind.DEFAULT);

        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(new CapturingClient(models))
            .model("opusplan")
            .maxTurns(3)
            .build();
        config.setPermissionModeSupplier(mode::get);
        DefaultQuerySession engine = new DefaultQuerySession(config);

        drain(engine, "hello");
        assertEquals(ModelNames.defaultMainLoopModel(), models.getLast(),
            "outside plan mode, opusplan resolves to the default Sonnet");

        mode.set(PermissionModeKind.PLAN);
        drain(engine, "make a plan");
        assertEquals(ModelNames.defaultOpusModel(), models.getLast(),
            "in plan mode, opusplan swaps the request to Opus");

        mode.set(PermissionModeKind.DEFAULT);
        drain(engine, "now execute");
        assertEquals(ModelNames.defaultMainLoopModel(), models.getLast(),
            "leaving plan mode drops back to Sonnet with no /model command");
    }

    @Test
    void plainAliasResolvesToConcreteId() {
        // Pre-existing adjacent gap fixed alongside opusplan: '/model sonnet'
        // stored the literal alias and sent it to the API verbatim.
        List<String> models = new CopyOnWriteArrayList<>();
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(new CapturingClient(models))
            .model("sonnet")
            .maxTurns(3)
            .build();
        DefaultQuerySession engine = new DefaultQuerySession(config);
        drain(engine, "hi");
        assertEquals(ModelNames.defaultMainLoopModel(), models.getFirst(),
            "alias must resolve to a concrete model id on the wire");
    }

    @Test
    void concreteIdUntouchedByPlanMode() {
        List<String> models = new CopyOnWriteArrayList<>();
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(new CapturingClient(models))
            .model("claude-opus-4-8")
            .maxTurns(3)
            .build();
        config.setPermissionModeSupplier(() -> PermissionModeKind.PLAN);
        DefaultQuerySession engine = new DefaultQuerySession(config);
        drain(engine, "hi");
        assertEquals("claude-opus-4-8", models.getFirst());
    }
}
