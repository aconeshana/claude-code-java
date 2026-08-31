package com.claudecode.services;

import com.claudecode.services.system.SleepPreventer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OtherServicesTest {

    @Test
    void sleepPreventerLifecycle() {
        var sp = new SleepPreventer();
        assertFalse(sp.isActive());
        sp.preventSleep();
        assertTrue(sp.isActive());
        sp.allowSleep();
        assertFalse(sp.isActive());
    }
}
