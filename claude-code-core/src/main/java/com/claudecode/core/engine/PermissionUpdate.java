package com.claudecode.core.engine;

import com.claudecode.core.model.PermissionModeKind;
import java.util.List;
import java.util.Objects;

/**
 * Dependency-free representation of a permission update proposed by a tool.
 * The engine owns this carrier because permission prompts cross the
 * core/tools/UI/SDK module boundary, while core cannot depend on the richer
 * permissions module.
 *
 * <ul>
 *   <li>{@code PermissionUpdate} union.</li>
 *   <li>the six
 *       discriminated update variants and their destinations.</li>
 *   <li>updates are first
 *       applied to the live permission context and persisted only for editable
 *       settings destinations.</li>
 * </ul>
 */
public sealed interface PermissionUpdate permits
        PermissionUpdate.AddRules,
        PermissionUpdate.ReplaceRules,
        PermissionUpdate.RemoveRules,
        PermissionUpdate.SetMode,
        PermissionUpdate.AddDirectories,
        PermissionUpdate.RemoveDirectories {

    Destination destination();


    enum Destination {
        USER_SETTINGS("userSettings"),
        PROJECT_SETTINGS("projectSettings"),
        LOCAL_SETTINGS("localSettings"),
        SESSION("session"),
        CLI_ARG("cliArg");

        private final String wireValue;

        Destination(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }


    enum Behavior {
        ALLOW("allow"), DENY("deny"), ASK("ask");

        private final String wireValue;

        Behavior(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }


    record RuleValue(String toolName, String ruleContent) {
        public RuleValue {
            Objects.requireNonNull(toolName, "toolName");
        }
    }

    record AddRules(List<RuleValue> rules, Behavior behavior,
                    Destination destination) implements PermissionUpdate {
        public AddRules {
            rules = List.copyOf(rules == null ? List.of() : rules);
            Objects.requireNonNull(behavior, "behavior");
            Objects.requireNonNull(destination, "destination");
        }
    }

    record ReplaceRules(List<RuleValue> rules, Behavior behavior,
                        Destination destination) implements PermissionUpdate {
        public ReplaceRules {
            rules = List.copyOf(rules == null ? List.of() : rules);
            Objects.requireNonNull(behavior, "behavior");
            Objects.requireNonNull(destination, "destination");
        }
    }

    record RemoveRules(List<RuleValue> rules, Behavior behavior,
                       Destination destination) implements PermissionUpdate {
        public RemoveRules {
            rules = List.copyOf(rules == null ? List.of() : rules);
            Objects.requireNonNull(behavior, "behavior");
            Objects.requireNonNull(destination, "destination");
        }
    }

    record SetMode(PermissionModeKind mode,
                   Destination destination) implements PermissionUpdate {
        public SetMode {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(destination, "destination");
        }
    }

    record AddDirectories(List<String> directories,
                          Destination destination) implements PermissionUpdate {
        public AddDirectories {
            directories = List.copyOf(directories == null ? List.of() : directories);
            Objects.requireNonNull(destination, "destination");
        }
    }

    record RemoveDirectories(List<String> directories,
                             Destination destination) implements PermissionUpdate {
        public RemoveDirectories {
            directories = List.copyOf(directories == null ? List.of() : directories);
            Objects.requireNonNull(destination, "destination");
        }
    }
}
