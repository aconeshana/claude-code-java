package com.claudecode.cli;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliResourceScopeTest {

    @Test
    void closesOwnedResourcesOnceInReverseRegistrationOrder() {
        List<String> closed = new ArrayList<>();
        CliResourceScope scope = new CliResourceScope();
        scope.own(() -> closed.add("plugin"));
        scope.own(() -> closed.add("settings"));
        scope.own(() -> closed.add("subscription"));

        scope.close();
        scope.close();

        assertEquals(List.of("subscription", "settings", "plugin"), closed);
    }

    @Test
    void attemptsEveryCloseWhenOneResourceFails() {
        List<String> closed = new ArrayList<>();
        CliResourceScope scope = new CliResourceScope();
        scope.own(() -> closed.add("plugin"));
        scope.own(() -> {
            closed.add("settings");
            throw new IllegalStateException("settings close failed");
        });
        scope.own(() -> closed.add("subscription"));

        scope.close();

        assertEquals(List.of("subscription", "settings", "plugin"), closed);
    }
}
