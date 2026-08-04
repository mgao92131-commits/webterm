package com.webterm.terminal.renderer;

import android.graphics.Path;

/** dotted/dashed 的有界相位 Path 缓存；只缓存几何，不持有 Canvas、Paint 或行对象。 */
final class TerminalDecorationPatternCache {
  static final int MAX_ENTRIES = 128;
  private static final int DOTTED = 1;
  private static final int DASHED = 2;
  private static final float DOTTED_PHASE_PX = 0.75f;
  private static final float DOTTED_PERIOD_PX = 3f;
  private static final int DASHED_PERIOD_PX = 7;
  private static final int DASHED_LENGTH_PX = 4;

  private final int[] kinds = new int[MAX_ENTRIES];
  private final int[] widths = new int[MAX_ENTRIES];
  private final int[] phases = new int[MAX_ENTRIES];
  private final boolean[] recentlyUsed = new boolean[MAX_ENTRIES];
  private final Path[] paths = new Path[MAX_ENTRIES];
  private int size;
  private int clockHand;
  private long buildCount;
  private long hitCount;

  Path dotted(int width, int left) {
    return get(DOTTED, Math.max(0, width), Math.floorMod(left, 3));
  }

  Path dashed(int width, int left) {
    return get(DASHED, Math.max(0, width), Math.floorMod(left, DASHED_PERIOD_PX));
  }

  int sizeForTest() { return size; }
  long buildCountForTest() { return buildCount; }
  long hitCountForTest() { return hitCount; }

  private Path get(int kind, int width, int phase) {
    for (int i = 0; i < size; i++) {
      if (kinds[i] == kind && widths[i] == width && phases[i] == phase) {
        recentlyUsed[i] = true;
        hitCount++;
        return paths[i];
      }
    }
    int index;
    if (size < MAX_ENTRIES) {
      index = size++;
    } else {
      index = victim();
    }
    kinds[index] = kind;
    widths[index] = width;
    phases[index] = phase;
    recentlyUsed[index] = true;
    paths[index] = kind == DOTTED ? buildDotted(width, phase) : buildDashed(width, phase);
    buildCount++;
    return paths[index];
  }

  private int victim() {
    for (int step = 0; step < MAX_ENTRIES * 2; step++) {
      int index = (clockHand + step) % MAX_ENTRIES;
      if (recentlyUsed[index]) {
        recentlyUsed[index] = false;
      } else {
        clockHand = (index + 1) % MAX_ENTRIES;
        return index;
      }
    }
    int result = clockHand;
    clockHand = (clockHand + 1) % MAX_ENTRIES;
    return result;
  }

  private static Path buildDotted(int width, int phase) {
    Path path = new Path();
    float radius = 0.75f;
    int firstIndex = (int) Math.floor(
        (phase - radius - DOTTED_PHASE_PX) / DOTTED_PERIOD_PX) - 1;
    float x = DOTTED_PHASE_PX + firstIndex * DOTTED_PERIOD_PX;
    float last = phase + width + radius;
    for (; x <= last; x += DOTTED_PERIOD_PX) {
      path.addCircle(x - phase, 0f, radius, Path.Direction.CW);
    }
    return path;
  }

  private static Path buildDashed(int width, int phase) {
    Path path = new Path();
    int firstIndex = (int) Math.floor((phase - DASHED_LENGTH_PX)
        / (float) DASHED_PERIOD_PX) - 1;
    int start = firstIndex * DASHED_PERIOD_PX;
    int last = phase + width + DASHED_LENGTH_PX;
    for (; start <= last; start += DASHED_PERIOD_PX) {
      path.moveTo(start - phase, 0f);
      path.lineTo(start - phase + DASHED_LENGTH_PX, 0f);
    }
    return path;
  }
}
