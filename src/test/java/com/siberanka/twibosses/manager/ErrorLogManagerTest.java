package com.siberanka.twibosses.manager;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ErrorLogManagerTest {
    @Test
    void boundsHostileThrowableGraphsAndDetectsCycles() {
        RuntimeException cyclic = new RuntimeException("cycle");
        RuntimeException root = new RuntimeException("root", cyclic);
        cyclic.initCause(root);

        StackTraceElement[] oversizedFrames = new StackTraceElement[400];
        Arrays.fill(oversizedFrames, new StackTraceElement("example.Attack", "flood", "Attack.java", 1));
        root.setStackTrace(oversizedFrames);

        String output = ErrorLogManager.stackTrace(root);

        assertTrue(output.length() <= 32_768);
        assertTrue(output.contains("stack frames truncated"));
        assertTrue(output.contains("CIRCULAR REFERENCE"));
    }
}
