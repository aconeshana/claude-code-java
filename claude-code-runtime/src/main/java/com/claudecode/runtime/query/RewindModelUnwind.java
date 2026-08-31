package com.claudecode.runtime.query;

import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.RefusalFallbackAnnouncement;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.model.ModelCatalog;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.process.SubprocessEnvironment;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/** Restores the model writer that was active before a sliced refusal-fallback banner. */
public final class RewindModelUnwind {

    private static final Pattern MODERN_CLAUDE_MODEL = Pattern.compile(
        "(claude-(?:fable|mythos|opus|sonnet|haiku)-\\d+(?:-\\d(?!\\d))?)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGACY_CLAUDE_MODEL = Pattern.compile(
        "(claude-3(?:-\\d)?-(?:opus|sonnet|haiku))", Pattern.CASE_INSENSITIVE);

    public enum KeepReason { NOT_FIRST_PARTY, WRITER_MISMATCH }

    public enum RestoreSource { TRANSCRIPT, INITIAL_MODEL, SETTINGS_FALLTHROUGH }

    public sealed interface ModelDecision permits Keep, Restore {}

    public record Keep(KeepReason reason) implements ModelDecision {}

    public record Restore(String value, RestoreSource source) implements ModelDecision {}

    public record Result(int bannersSliced, ModelDecision model,
                         String lastSlicedFallbackModel) {}

    private RewindModelUnwind() {}

    /**
     * Mirrors the 2.1.197 rewind writer guard: only first-party sessions whose current writer still
     * matches the last sliced fallback are unwound. Older transcripts without an origin model use
     * the initial model setting, or clear the override to fall through to live settings.
     */
    public static Result evaluate(List<Message> keptMessages, List<Message> slicedMessages,
                                  String currentOverride, boolean firstParty,
                                  String initialModel, Predicate<String> restorableModel) {
        List<SystemMessage> banners = slicedMessages.stream()
            .filter(SystemMessage.class::isInstance)
            .map(SystemMessage.class::cast)
            .filter(message -> Strings.CS.equals(
                RefusalFallbackAnnouncement.SUBTYPE, message.subtype()))
            .toList();
        if (banners.isEmpty()) return null;

        String fallbackModel = banners.getLast().fallbackModel();
        if (!firstParty) {
            return new Result(banners.size(), new Keep(KeepReason.NOT_FIRST_PARTY), fallbackModel);
        }
        if (!sameWriter(currentOverride, fallbackModel)) {
            return new Result(banners.size(), new Keep(KeepReason.WRITER_MISMATCH), fallbackModel);
        }

        String transcriptModel = latestRestorableAssistantModel(
            keptMessages, initialModel, currentOverride,
            restorableModel != null ? restorableModel : _ -> true);
        Restore restore;
        if (transcriptModel != null) {
            restore = new Restore(transcriptModel, RestoreSource.TRANSCRIPT);
        } else if (initialModel != null) {
            restore = new Restore(initialModel, RestoreSource.INITIAL_MODEL);
        } else {
            restore = new Restore(null, RestoreSource.SETTINGS_FALLTHROUGH);
        }
        return new Result(banners.size(), restore, fallbackModel);
    }

    private static String latestRestorableAssistantModel(
            List<Message> messages, String initialModel, String currentModelSetting,
            Predicate<String> restorableModel) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (!(messages.get(i) instanceof AssistantMessage assistant)
                    || Boolean.TRUE.equals(assistant.isMeta())
                    || assistant.message() == null) {
                continue;
            }
            String model = assistant.message().model();
            if (Strings.CS.equals(MessageConstants.SYNTHETIC_MODEL, model)) {
                continue;
            }
            if (StringUtils.isBlank(model)
                    || modeDependentSetting(currentModelSetting, model)
                    || isRetiredFirstPartyModel(model)
                    || !restorableModel.test(model)) return null;
            if (initialModelUsesOneMillion(initialModel)
                    && supportsOneMillionContext(model)
                    && sameWriter(initialModel, model)
                    && !hasOneMillionTag(model)) {
                return model + "[1m]";
            }
            return model;
        }
        return null;
    }

    private static boolean modeDependentSetting(String modelSetting, String responseModel) {
        String setting = modelSetting == null ? null : modelSetting.trim().toLowerCase(Locale.ROOT);
        String family = family(responseModel);
        return Strings.CS.equals("opusplan", setting)
                && (Strings.CS.equals("opus", family) || Strings.CS.equals("sonnet", family))
            || Strings.CS.equals("haiku", setting)
                && (Strings.CS.equals("haiku", family) || Strings.CS.equals("sonnet", family));
    }

    private static boolean sameWriter(String left, String right) {
        if (left == null) return false;
        if (right == null) return false;
        String normalizedLeft = normalized(ModelCatalog.resolve(left));
        String normalizedRight = normalized(ModelCatalog.resolve(right));
        if (Strings.CS.equals(normalizedLeft, normalizedRight)) return true;
        String canonicalLeft = canonicalModel(normalizedLeft);
        return canonicalLeft != null
            && Strings.CS.equals(canonicalLeft, canonicalModel(normalizedRight));
    }

    private static String family(String model) {
        String canonical = canonicalModel(normalized(ModelCatalog.resolve(model)));
        if (canonical == null) return null;
        if (Strings.CS.contains(canonical, "opus")) return "opus";
        if (Strings.CS.contains(canonical, "sonnet")) return "sonnet";
        if (Strings.CS.contains(canonical, "haiku")) return "haiku";
        if (Strings.CS.contains(canonical, "fable")) return "fable";
        return null;
    }

    private static boolean initialModelUsesOneMillion(String model) {
        return hasOneMillionTag(model) || hasOneMillionTag(ModelCatalog.resolve(model));
    }

    private static boolean supportsOneMillionContext(String model) {
        if (EnvUtils.isEnvTruthy(
                SubprocessEnvironment.get("CLAUDE_CODE_DISABLE_1M_CONTEXT"))) {
            return false;
        }
        String canonical = canonicalModel(normalized(ModelCatalog.resolve(model)));
        if (canonical == null || Strings.CS.startsWith(canonical, "claude-3-")) return false;
        return !Strings.CS.equalsAny(canonical,
            "claude-opus-4-0",
            "claude-opus-4-1",
            "claude-opus-4-5",
            "claude-haiku-4-5");
    }

    private static boolean isRetiredFirstPartyModel(String model) {
        String canonical = canonicalModel(normalized(ModelCatalog.resolve(model)));
        if (canonical == null) return false;
        boolean legacyRemapEnabled = !EnvUtils.isEnvTruthy(
            SubprocessEnvironment.get("CLAUDE_CODE_DISABLE_LEGACY_MODEL_REMAP"));
        if (legacyRemapEnabled && Strings.CS.equalsAny(
                canonical, "claude-opus-4-0", "claude-opus-4-1")) {
            return true;
        }
        LocalDate retirement = switch (canonical) {
            case "claude-opus-4-0", "claude-sonnet-4-0" -> LocalDate.of(2026, 6, 15);
            case "claude-3-opus" -> LocalDate.of(2026, 1, 5);
            case "claude-3-7-sonnet", "claude-3-5-haiku" ->
                LocalDate.of(2026, 2, 19);
            default -> null;
        };
        return retirement != null && !LocalDate.now().isBefore(retirement);
    }

    private static String canonicalModel(String normalizedModel) {
        if (normalizedModel == null) return null;
        Matcher legacy = LEGACY_CLAUDE_MODEL.matcher(normalizedModel);
        if (legacy.find()) return legacy.group(1).toLowerCase(Locale.ROOT);
        Matcher modern = MODERN_CLAUDE_MODEL.matcher(normalizedModel);
        if (modern.find()) {
            String canonical = modern.group(1).toLowerCase(Locale.ROOT);
            if (Strings.CS.equals("claude-opus-4", canonical)
                    || Strings.CS.equals("claude-sonnet-4", canonical)) {
                return canonical + "-0";
            }
            return canonical;
        }
        return normalizedModel.replaceFirst("-\\d{8}$", "");
    }

    private static boolean hasOneMillionTag(String model) {
        return model != null && Strings.CI.endsWith(model.trim(), "[1m]");
    }

    private static String normalized(String model) {
        if (model == null) return null;
        String normalized = ModelNames.normalizeModelStringForApi(model).trim();
        return normalized.toLowerCase(Locale.ROOT);
    }
}
