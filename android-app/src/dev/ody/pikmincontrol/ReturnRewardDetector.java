package dev.ody.pikmincontrol;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Finds the mint-outline cards used by completed expedition rewards. */
final class ReturnRewardDetector {
    private static final int SAMPLE_STEP = 2;

    private ReturnRewardDetector() {}

    static Target find(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() < 400 || bitmap.getHeight() < 700) return null;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int sampledWidth = width / SAMPLE_STEP;
        int sampledHeight = height / SAMPLE_STEP;
        boolean[] mint = new boolean[sampledWidth * sampledHeight];
        for (int y = (int) (sampledHeight * 0.42f); y < (int) (sampledHeight * 0.93f); y++) {
            for (int x = 0; x < sampledWidth; x++) {
                int color = bitmap.getPixel(x * SAMPLE_STEP, y * SAMPLE_STEP);
                mint[y * sampledWidth + x] = isMint(color);
            }
        }

        boolean[] visited = new boolean[mint.length];
        List<Target> targets = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int index = 0; index < mint.length; index++) {
            if (!mint[index] || visited[index]) continue;
            visited[index] = true;
            queue.add(index);
            int minX = index % sampledWidth;
            int maxX = minX;
            int minY = index / sampledWidth;
            int maxY = minY;
            int pixels = 0;
            while (!queue.isEmpty()) {
                int point = queue.removeFirst();
                int x = point % sampledWidth;
                int y = point / sampledWidth;
                pixels++;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                visit(point - 1, x > 0, mint, visited, queue);
                visit(point + 1, x + 1 < sampledWidth, mint, visited, queue);
                visit(point - sampledWidth, y > 0, mint, visited, queue);
                visit(point + sampledWidth, y + 1 < sampledHeight, mint, visited, queue);
            }
            int componentWidth = (maxX - minX + 1) * SAMPLE_STEP;
            int componentHeight = (maxY - minY + 1) * SAMPLE_STEP;
            if (pixels >= 70 && componentWidth >= width * 0.18f && componentWidth <= width * 0.62f
                    && componentHeight >= height * 0.075f && componentHeight <= height * 0.26f) {
                targets.add(new Target((minX + maxX) * SAMPLE_STEP / 2,
                        (minY + maxY) * SAMPLE_STEP / 2, componentWidth, componentHeight));
            }
        }
        Target best = null;
        for (Target target : targets) {
            if (best == null || target.y < best.y) best = target;
        }
        return best;
    }

    private static void visit(int index, boolean inBounds, boolean[] mint, boolean[] visited,
            ArrayDeque<Integer> queue) {
        if (inBounds && mint[index] && !visited[index]) { visited[index] = true; queue.addLast(index); }
    }

    private static boolean isMint(int color) {
        float[] hsv = new float[3];
        Color.RGBToHSV(Color.red(color), Color.green(color), Color.blue(color), hsv);
        return hsv[0] >= 145f && hsv[0] <= 190f && hsv[1] >= 0.05f && hsv[1] <= 0.95f
                && hsv[2] >= 0.55f;
    }

    static final class Target {
        final int x;
        final int y;
        final int width;
        final int height;

        Target(int x, int y, int width, int height) {
            this.x = x; this.y = y; this.width = width; this.height = height;
        }
    }
}
