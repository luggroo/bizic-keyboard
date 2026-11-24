package biz.tujia.ime;

import java.util.List;

/** Rasterizes the same binary ink and antialiased 64×64 input used by the browser comparison. */
final class HandwritingImage {
    private static final int RASTER_SIZE = 360;
    private static final int INPUT_SIZE = 64;
    private static final int CONTENT_SIZE = 56;
    private static final double RADIUS = RASTER_SIZE * 0.044 / 2;

    private final byte[] ink = new byte[RASTER_SIZE * RASTER_SIZE];
    private int minX = RASTER_SIZE;
    private int minY = RASTER_SIZE;
    private int maxX = -1;
    private int maxY = -1;

    static float[][] preprocess(List<float[]> strokes, int padWidth, int padHeight) {
        HandwritingImage raster = new HandwritingImage();
        double scale = (double) RASTER_SIZE / Math.max(padWidth, padHeight);
        double offsetX = (RASTER_SIZE - padWidth * scale) / 2;
        double offsetY = (RASTER_SIZE - padHeight * scale) / 2;
        int points = 0;
        for (float[] stroke : strokes) {
            if (stroke.length < 2) continue;
            double firstX = stroke[0] * scale + offsetX;
            double firstY = stroke[1] * scale + offsetY;
            double lastX = firstX;
            double lastY = firstY;
            boolean moved = false;
            for (int i = 2; i < stroke.length; i += 2) {
                double x = stroke[i] * scale + offsetX;
                double y = stroke[i + 1] * scale + offsetY;
                if (x == lastX && y == lastY) continue;
                raster.draw(lastX, lastY, x, y);
                if (moved) raster.draw(lastX, lastY, lastX, lastY);
                moved = true;
                points++;
                lastX = x;
                lastY = y;
            }
            if (!moved) raster.draw(firstX, firstY, firstX, firstY);
            points++;
        }
        // A stray tap is not a finished character. Dots inside a character remain visible.
        return points < 2 || raster.maxX < raster.minX ? null : raster.resize();
    }

    private void draw(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double lengthSquared = dx * dx + dy * dy;
        int left = Math.max(0, (int) Math.floor(Math.min(x1, x2) - RADIUS));
        int right = Math.min(RASTER_SIZE - 1, (int) Math.ceil(Math.max(x1, x2) + RADIUS));
        int top = Math.max(0, (int) Math.floor(Math.min(y1, y2) - RADIUS));
        int bottom = Math.min(RASTER_SIZE - 1, (int) Math.ceil(Math.max(y1, y2) + RADIUS));
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                double px = x + 0.5 - x1;
                double py = y + 0.5 - y1;
                double fraction = lengthSquared == 0 ? 0 : (px * dx + py * dy) / lengthSquared;
                if (fraction < 0 || fraction > 1) continue;
                double acrossX = px - fraction * dx;
                double acrossY = py - fraction * dy;
                if (acrossX * acrossX + acrossY * acrossY > RADIUS * RADIUS) continue;
                ink[y * RASTER_SIZE + x] = (byte) 255;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
    }

    private float[][] resize() {
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        double scale = (double) CONTENT_SIZE / Math.max(width, height);
        int scaledWidth = Math.max(1, (int) Math.round(width * scale));
        int scaledHeight = Math.max(1, (int) Math.round(height * scale));
        int[][] rows = new int[height][scaledWidth];
        for (int x = 0; x < scaledWidth; x++) {
            Kernel kernel = new Kernel(width, scaledWidth, x);
            for (int y = 0; y < height; y++) {
                int from = (y + minY) * RASTER_SIZE + minX + kernel.start;
                double sum = 0;
                for (int i = 0; i < kernel.weights.length; i++) sum += (ink[from + i] & 0xff) * kernel.weights[i];
                rows[y][x] = (int) Math.round(sum);
            }
        }
        float[][] input = new float[INPUT_SIZE][INPUT_SIZE];
        int left = (INPUT_SIZE - scaledWidth) / 2;
        int top = (INPUT_SIZE - scaledHeight) / 2;
        for (int y = 0; y < scaledHeight; y++) {
            Kernel kernel = new Kernel(height, scaledHeight, y);
            for (int x = 0; x < scaledWidth; x++) {
                double sum = 0;
                for (int i = 0; i < kernel.weights.length; i++) sum += rows[kernel.start + i][x] * kernel.weights[i];
                input[top + y][left + x] = Math.round(sum) / 255f;
            }
        }
        return input;
    }

    /** Separable antialiased bilinear resampling. */
    private static final class Kernel {
        final int start;
        final double[] weights;

        Kernel(int sourceSize, int targetSize, int index) {
            double scale = (double) sourceSize / targetSize;
            double footprint = Math.max(1, scale);
            double center = (index + 0.5) * scale;
            start = Math.max(0, (int) Math.floor(center - footprint + 0.5));
            int stop = Math.min(sourceSize, (int) Math.floor(center + footprint + 0.5));
            weights = new double[stop - start];
            double sum = 0;
            for (int i = 0; i < weights.length; i++) {
                weights[i] = Math.max(0, 1 - Math.abs(start + i + 0.5 - center) / footprint);
                sum += weights[i];
            }
            for (int i = 0; i < weights.length; i++) weights[i] /= sum;
        }
    }
}
