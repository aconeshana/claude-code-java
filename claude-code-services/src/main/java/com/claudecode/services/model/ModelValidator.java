package com.claudecode.services.model;

import org.apache.commons.lang3.Strings;

import com.claudecode.api.ApiException;
import com.claudecode.api.ApiMessage;
import com.claudecode.api.ApiMessageTiming;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.LlmClient;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.model.ModelAliases;
import com.claudecode.core.process.SubprocessEnvironment;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates a custom model id for {@code /model <name>} by probing the API, with alias / env /
 * cache short-circuits before any network call.
 */
public final class ModelValidator {

    /** Validation outcome. {@code error} is {@code null} when {@code valid}. */
    public record Result(boolean valid, String error) {
        public static Result ok() { return new Result(true, null); }
        public static Result invalid(String error) { return new Result(false, error); }
    }


    private static final Map<String, Boolean> VALID_MODEL_CACHE = new ConcurrentHashMap<>();

    private final LlmClient llmClient;

    public ModelValidator(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /** Validate a model id. Alias / env / cache hits skip the network probe. */
    public Result validate(String model) {
        String normalized = model == null ? "" : model.trim();

        if (normalized.isEmpty()) {
            return Result.invalid("Model name cannot be empty");
        }

        if (!ModelAllowlist.isAllowed(normalized)) {
            return Result.invalid(ModelAllowlist.rejectionMessage(normalized));
        }

        // Known alias — predefined, always valid, no API call.
        if (ModelAliases.isModelAlias(normalized)) {
            return Result.ok();
        }

        // ANTHROPIC_CUSTOM_MODEL_OPTION — pre-validated by the user.
        String customOption = SubprocessEnvironment.get("ANTHROPIC_CUSTOM_MODEL_OPTION");
        if (normalized.equals(customOption)) {
            return Result.ok();
        }

        // Cache.
        if (Boolean.TRUE.equals(VALID_MODEL_CACHE.get(normalized))) {
            return Result.ok();
        }

        // No client to probe with (headless / test) — can't validate offline; accept.
        if (llmClient == null) {
            return Result.ok();
        }

        try {
            CreateMessageRequest req = CreateMessageRequest.builder()
                .model(normalized)
                .maxTokens(1)
                .messages(List.of(new CreateMessageRequest.RequestMessage("user", "Hi")))
                .stream(false)
                .querySource("model_validation")
                .build();
            long startedAt = System.currentTimeMillis();
            ApiMessage response = llmClient.createMessage(req);
            long completedAt = System.currentTimeMillis();
            SessionCostState.get().recordApiRequest(
                response != null && response.model() != null ? response.model() : normalized,
                response != null ? response.usage() : null,
                Math.max(0L, completedAt - startedAt),
                Math.max(0L, completedAt
                    - ApiMessageTiming.lastAttemptStartMs(response, startedAt)));
            VALID_MODEL_CACHE.put(normalized, true);
            return Result.ok();
        } catch (ApiException e) {
            return handleValidationError(e, normalized);
        } catch (Exception e) {
            return Result.invalid("Unable to validate model: " + e.getMessage());
        }
    }

    /**
     * Maps an {@link ApiException} to a user-facing message.
     */
    private static Result handleValidationError(ApiException e, String model) {
        int status = e.statusCode();
        String type = e.errorType();

        if (status == 404 || Strings.CS.equals("not_found_error", type)) {
            return Result.invalid("Model '" + model + "' not found");
        }
        if (status == 401 || status == 403) {
            return Result.invalid("Authentication failed. Please check your API credentials.");
        }
        if (status == 0) {
            return Result.invalid("Network error. Please check your internet connection.");
        }
        return Result.invalid("API error: " + e.getMessage());
    }
}
