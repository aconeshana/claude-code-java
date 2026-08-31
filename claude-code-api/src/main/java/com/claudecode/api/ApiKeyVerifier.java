package com.claudecode.api;

import com.claudecode.core.model.ModelNames;
import com.claudecode.core.process.SubprocessEnvironment;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;


public final class ApiKeyVerifier {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyVerifier.class);

    private static final long VERIFY_TIMEOUT_MS = 30_000;

    private ApiKeyVerifier() {}

    /**
     * Returns {@code true} if the key is usable or verification is skipped.
     * Returns {@code false} only when the API returns an explicit
     * {@code invalid x-api-key} authentication error. Any other failure is
     * rethrown so it is not silently swallowed as a bad key.
     */
    public static boolean verify(String apiKey, boolean nonInteractive, LlmClient client) {
        if (nonInteractive) return true;
        if (StringUtils.isBlank(apiKey)) return true;
        CreateMessageRequest req = CreateMessageRequest.builder()
            .model(ModelNames.defaultHaikuModel(SubprocessEnvironment::get))
            .maxTokens(1)
            .temperature(1.0)
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", "test")))
            .promptCachingEnabled(false)
            .build();
        try {
            client.createMessage(req, VERIFY_TIMEOUT_MS);
            return true;
        } catch (ApiException e) {
            if (e.statusCode() == 401
                && Strings.CS.contains(String.valueOf(e.getMessage()), "invalid x-api-key")) {
                return false;
            }
            throw e;
        }
    }
}
