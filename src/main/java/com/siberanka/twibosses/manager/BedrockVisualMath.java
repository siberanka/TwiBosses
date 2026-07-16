package com.siberanka.twibosses.manager;

final class BedrockVisualMath {
    private BedrockVisualMath() {
    }

    static int cell(double coordinate, double cellSize) {
        double safeCellSize = Double.isFinite(cellSize) && cellSize > 0.0 ? cellSize : 1.0;
        return (int)Math.floor(coordinate / safeCellSize);
    }

    static long cellKey(int x, int z) {
        return ((long)x << 32) ^ (z & 0xffffffffL);
    }

    static float rotationDifference(float first, float second) {
        float difference = Math.abs(first - second) % 360.0F;
        return difference > 180.0F ? 360.0F - difference : difference;
    }

    static boolean withinSquaredDistance(double firstX, double firstY, double firstZ,
                                         double secondX, double secondY, double secondZ,
                                         double radiusSquared) {
        if (!Double.isFinite(firstX) || !Double.isFinite(firstY) || !Double.isFinite(firstZ)
                || !Double.isFinite(secondX) || !Double.isFinite(secondY) || !Double.isFinite(secondZ)
                || !Double.isFinite(radiusSquared) || radiusSquared < 0.0) {
            return false;
        }
        double deltaX = firstX - secondX;
        double deltaY = firstY - secondY;
        double deltaZ = firstZ - secondZ;
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= radiusSquared;
    }
}
