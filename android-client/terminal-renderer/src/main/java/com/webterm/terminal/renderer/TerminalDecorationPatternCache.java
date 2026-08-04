package com.webterm.terminal.renderer;

import android.graphics.Path;

/** dotted/dashed 的有界相位 Path 缓存；只缓存几何，不持有 Canvas、Paint 或行对象。 */
final class TerminalDecorationPatternCache {
  static final int MAX_ENTRIES = 128;
  private static final int DOTTED = 1;
  private static final int DASHED = 2;
  private static final int CURLY = 3;
  private static final float DOTTED_PHASE_PX = 0.75f;
  private static final float DOTTED_PERIOD_PX = 3f;
  private static final int DASHED_PERIOD_PX = 7;
  private static final int DASHED_LENGTH_PX = 4;
  private static final int CURLY_PERIOD_PX = 8;
  private static final float[] CURLY_Y = {
      0f, 0.70710677f, 1f, 0.70710677f,
      0f, -0.70710677f, -1f, -0.70710677f
  };

  private final int[] kinds = new int[MAX_ENTRIES];
  private final int[] widths = new int[MAX_ENTRIES];
  private final int[] phases = new int[MAX_ENTRIES];
  private final boolean[] recentlyUsed = new boolean[MAX_ENTRIES];
  private final Path[] paths = new Path[MAX_ENTRIES];
  private int size;
  private int clockHand;
  private long buildCount;
  private long hitCount;
  private boolean lastLookupHit;
  private int lastBuildSegmentCount;

  Path dotted(int width, int left) {
    return get(DOTTED, Math.max(0, width), Math.floorMod(left, 3));
  }

  Path dashed(int width, int left) {
    return get(DASHED, Math.max(0, width), Math.floorMod(left, DASHED_PERIOD_PX));
  }

  Path curly(int width, int left) {
    return get(CURLY, Math.max(0, width), Math.floorMod(left, CURLY_PERIOD_PX));
  }

  int sizeForTest() { return size; }
  long buildCountForTest() { return buildCount; }
  long hitCountForTest() { return hitCount; }
  boolean wasLastLookupHit() { return lastLookupHit; }
  int lastBuildSegmentCount() { return lastBuildSegmentCount; }

  private Path get(int kind, int width, int phase) {
    lastLookupHit = false;
    lastBuildSegmentCount = 0;
    for (int i = 0; i < size; i++) {
      if (kinds[i] == kind && widths[i] == width && phases[i] == phase) {
        recentlyUsed[i] = true;
        hitCount++;
        lastLookupHit = true;
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
    if (kind == DOTTED) {
      paths[index] = buildDotted(width, phase);
    } else if (kind == DASHED) {
      paths[index] = buildDashed(width, phase);
    } else {
      lastBuildSegmentCount = width + 4;
      paths[index] = buildCurly(width, phase);
    }
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

  private static Path buildCurly(int width, int phase) {
    Path path = new Path();
    int start = -2;
    int end = width + 2;
    for (int x = start; x < end; x++) {
      path.moveTo(x, curlyOffset(phase + x));
      path.lineTo(x + 1, curlyOffset(phase + x + 1));
    }
    return path;
  }

  private static float curlyOffset(int absoluteX) {
    return CURLY_Y[Math.floorMod(absoluteX, CURLY_PERIOD_PX)];
  }
}
