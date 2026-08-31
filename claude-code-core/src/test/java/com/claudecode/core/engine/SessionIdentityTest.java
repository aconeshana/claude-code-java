package com.claudecode.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Regression guard for the shared-reference design: two independently
 * constructed {@link SessionIdentity} instances must never influence each
 * other. If this ever starts failing, someone has slipped a JVM-wide static
 * back into the implementation — exactly the "static final Path" style
 * cross-test pollution this class was designed to avoid.
 */
class SessionIdentityTest {

    @Test
    void twoInstances_areIndependent() {
        SessionIdentity a = SessionIdentity.newRandom();
        SessionIdentity b = SessionIdentity.newRandom();
        assertNotEquals(a.get(), b.get());

        a.set("switched-a");
        assertEquals("switched-a", a.get());
        assertNotEquals("switched-a", b.get());
    }

    @Test
    void of_usesTheGivenId() {
        assertEquals("existing-id", SessionIdentity.of("existing-id").get());
    }

    @Test
    void regenerate_returnsAndStoresANewId() {
        SessionIdentity identity = SessionIdentity.of("initial");
        String regenerated = identity.regenerate();
        assertEquals(regenerated, identity.get());
        assertNotEquals("initial", identity.get());
    }

    @Test
    void set_rejectsBlank() {
        SessionIdentity identity = SessionIdentity.newRandom();
        assertThrows(IllegalArgumentException.class, () -> identity.set(""));
        assertThrows(IllegalArgumentException.class, () -> identity.set(null));
    }

    @Test
    void of_rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> SessionIdentity.of(""));
        assertThrows(IllegalArgumentException.class, () -> SessionIdentity.of(null));
    }

    @Test
    void changeSubscribersFollowSetAndRegenerateUntilClosed() throws Exception {
        SessionIdentity identity = SessionIdentity.of("initial");
        List<String> observed = new ArrayList<>();
        AutoCloseable subscription = identity.subscribeChanges(observed::add);

        identity.set("resumed");
        String regenerated = identity.regenerate();
        subscription.close();
        identity.set("ignored");

        assertEquals(List.of("resumed", regenerated), observed);
    }
}
