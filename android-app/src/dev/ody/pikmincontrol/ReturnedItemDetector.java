package dev.ody.pikmincontrol;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayDeque;

/** Detects a large returned fruit shown over the Pikmin squad field. */
final class ReturnedItemDetector {
    private static final int STEP = 4;

    private ReturnedItemDetector() {}

    static Target find(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() < 400 || bitmap.getHeight() < 700) return null;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int gridWidth = width / STEP;
        int gridHeight = height / STEP;
        boolean[] pixels = new boolean[gridWidth * gridHeight];
        boolean[] visited = new boolean[pixels.length];
        int top = Math.round(gridHeight * 0.36f);
        int bottom = Math.round(gridHeight * 0.64f);
        int left = Math.round(gridWidth * 0.24f);
        int right = Math.round(gridWidth * 0.76f);
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                pixels[y * gridWidth + x] = itemKind(bitmap.getPixel(x * STEP, y * STEP)) != Kind.NONE;
            }
        }

        Target best = null;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int index = 0; index < pixels.length; index++) {
            if (!pixels[index] || visited[index]) continue;
            visited[index] = true;
            queue.add(index);
            int minX = index % gridWidth, maxX = minX;
            int minY = index / gridWidth, maxY = minY;
            long sumX = 0, sumY = 0;
            int count = 0;
            while (!queue.isEmpty()) {
                int point = queue.removeFirst();
                int x = point % gridWidth, y = point / gridWidth;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                sumX += x; sumY += y; count++;
                visit(point - 1, x > 0, pixels, visited, queue);
                visit(point + 1, x + 1 < gridWidth, pixels, visited, queue);
                visit(point - gridWidth, y > 0, pixels, visited, queue);
                visit(point + gridWidth, y + 1 < gridHeight, pixels, visited, queue);
            }
            int componentWidth = (maxX - minX + 1) * STEP;
            int componentHeight = (maxY - minY + 1) * STEP;
            int centerX = Math.round(sumX * STEP / (float) count);
            int centerY = Math.round(sumY * STEP / (float) count);
            if (count < 40 || componentWidth < width * 0.065f || componentWidth > width * 0.42f
                    || componentHeight < height * 0.045f || componentHeight > height * 0.24f
                    || centerX < width * 0.34f || centerX > width * 0.66f
                    || centerY < height * 0.38f || centerY > height * 0.64f) continue;
            Kind kind = itemKind(bitmap.getPixel(centerX, centerY));
            if (kind == Kind.NONE) kind = dominantKind(bitmap, minX * STEP, minY * STEP,
                    maxX * STEP, maxY * STEP);
            Target candidate = new Target(centerX, centerY, kind);
            if (best == null || componentWidth * componentHeight > best.area) {
                candidate.area = componentWidth * componentHeight;
                best = candidate;
            }
        }
        return best;
    }

    private static void visit(int index, boolean inBounds, boolean[] pixels, boolean[] visited,
            ArrayDeque<Integer> queue) {
        if (inBounds && pixels[index] && !visited[index]) { visited[index] = true; queue.add(index); }
    }

    private static Kind itemKind(int color) {
        int red = Color.red(color), green = Color.green(color), blue = Color.blue(color);
        if (red >= 125 && red >= green * 1.25f && red >= blue * 1.35f) return Kind.FRUIT;
        if (red >= 110 && green >= 80 && blue <= 130 && red + green >= 260) return Kind.POT;
        return Kind.NONE;
    }

    private static Kind dominantKind(Bitmap bitmap, int left, int top, int right, int bottom) {
        int fruit = 0, pot = 0;
        for (int y = top; y <= bottom && y < bitmap.getHeight(); y += STEP) {
            for (int x = left; x <= right && x < bitmap.getWidth(); x += STEP) {
                Kind kind = itemKind(bitmap.getPixel(x, y));
                if (kind == Kind.FRUIT) fruit++;
                else if (kind == Kind.POT) pot++;
            }
        }
        return fruit >= pot && fruit > 0 ? Kind.FRUIT : pot > 0 ? Kind.POT : Kind.NONE;
    }

    static final class Target {
        final int x, y;
        final Kind kind;
        int area;
        Target(int x, int y, Kind kind) { this.x = x; this.y = y; this.kind = kind; }
    }

    enum Kind { NONE, FRUIT, POT }
}
