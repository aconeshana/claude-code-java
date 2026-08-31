package com.claudecode.tools.bundled;

import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BundledResourceCatalogTest {

    @Test
    void currentReleaseIsBoundInOnePlace() {
        BundledResourceCatalog catalog = BundledResourceCatalog.current();

        assertEquals("2.1.197", catalog.version());
        assertEquals("deep-research", catalog.skills().getFirst().name());
        assertEquals(List.of("code-review", "deep-research"), catalog.workflows().stream()
            .map(BundledResourceCatalog.WorkflowResource::name)
            .toList());
    }

    @Test
    void explicitReleaseCanBeLoadedWithoutChangingBusinessClasses() {
        BundledResourceCatalog catalog = BundledResourceCatalog.forVersion("9.9.9");

        assertEquals("9.9.9", catalog.version());
        assertEquals("TEST SKILL\n", catalog.readText(catalog.skills().getFirst().path()));
        assertTrue(Strings.CS.contains(
            catalog.readText(catalog.workflows().getFirst().path()), "TEST WORKFLOW"));
    }

    @Test
    void rejectsUnsafeOrMissingReleaseNames() {
        assertThrows(IllegalArgumentException.class,
            () -> BundledResourceCatalog.forVersion("../2.1.197"));
        assertThrows(IllegalStateException.class,
            () -> BundledResourceCatalog.forVersion("8.8.8"));
    }
}
