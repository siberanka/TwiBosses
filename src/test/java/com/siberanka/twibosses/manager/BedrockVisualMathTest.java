package com.siberanka.twibosses.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BedrockVisualMathTest {
    @Test
    void mapsNegativeCoordinatesWithFloorSemantics() {
        assertEquals(0, BedrockVisualMath.cell(127.99, 128.0));
        assertEquals(-1, BedrockVisualMath.cell(-0.01, 128.0));
        assertEquals(-1, BedrockVisualMath.cell(-128.0, 128.0));
        assertEquals(-2, BedrockVisualMath.cell(-128.01, 128.0));
        assertNotEquals(BedrockVisualMath.cellKey(-1, 0), BedrockVisualMath.cellKey(0, -1));
    }

    @Test
    void calculatesWrappedRotationDifference() {
        assertEquals(2.0F, BedrockVisualMath.rotationDifference(359.0F, 1.0F));
        assertEquals(20.0F, BedrockVisualMath.rotationDifference(10.0F, 350.0F));
        assertEquals(180.0F, BedrockVisualMath.rotationDifference(0.0F, 180.0F));
    }

    @Test
    void rejectsMalformedCoordinatesAndHonorsRadiusBoundary() {
        assertTrue(BedrockVisualMath.withinSquaredDistance(0, 0, 0, 3, 4, 0, 25));
        assertFalse(BedrockVisualMath.withinSquaredDistance(0, 0, 0, 3, 4, 0, 24.99));
        assertFalse(BedrockVisualMath.withinSquaredDistance(Double.NaN, 0, 0, 0, 0, 0, 25));
        assertFalse(BedrockVisualMath.withinSquaredDistance(0, 0, 0, 0, 0, 0, Double.POSITIVE_INFINITY));
        assertFalse(BedrockVisualMath.withinSquaredDistance(0, 0, 0, 0, 0, 0, -1));
    }
}
