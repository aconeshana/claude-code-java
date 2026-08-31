package com.claudecode.ui.lanterna.repl;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.terminal.ExtendedTerminal;
import com.googlecode.lanterna.terminal.MouseCaptureMode;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalMouseModeLifecycleTest {

    @Test
    void disablesAndFlushesMouseReportingBeforeTerminalHandoff() throws Exception {
        List<String> calls = new ArrayList<>();
        ExtendedTerminal terminal = fakeTerminal(calls);

        TerminalMouseModeLifecycle.disableBeforeHandoff(terminal);

        assertEquals(List.of("mouse:null", "flush"), calls);
    }

    @Test
    void restoresClickReleaseDragCaptureAfterTerminalReturns() throws Exception {
        List<String> calls = new ArrayList<>();
        ExtendedTerminal terminal = fakeTerminal(calls);

        TerminalMouseModeLifecycle.enableForTui(terminal);

        assertEquals(List.of("mouse:" + MouseCaptureMode.CLICK_RELEASE_DRAG, "flush"), calls);
    }

    private static ExtendedTerminal fakeTerminal(List<String> calls) {
        return (ExtendedTerminal) Proxy.newProxyInstance(
            TerminalMouseModeLifecycleTest.class.getClassLoader(),
            new Class<?>[] { ExtendedTerminal.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "setMouseCaptureMode" -> {
                    calls.add("mouse:" + args[0]);
                    yield null;
                }
                case "flush" -> {
                    calls.add("flush");
                    yield null;
                }
                case "getCursorPosition" -> TerminalPosition.of(0, 0);
                case "getTerminalSize" -> new TerminalSize(120, 40);
                case "enquireTerminal" -> new byte[0];
                case "pollInput", "readInput", "newTextGraphics" -> null;
                case "toString" -> "fake-terminal";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            });
    }
}
