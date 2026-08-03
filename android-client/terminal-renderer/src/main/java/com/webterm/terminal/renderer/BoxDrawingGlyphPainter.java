package com.webterm.terminal.renderer;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * 使用 cell 几何绘制 U+2500..U+257F Box Drawing。
 *
 * <p>字符描述在类初始化时建立为静态表；逐 cell 绘制只读取描述并复用 Paint/Path，不
 * 依赖系统字体的 glyph metrics。混合线宽使用四个方向的 stroke 描述，绘制算法保持统一。</p>
 */
final class BoxDrawingGlyphPainter {
  private static final int FIRST = 0x2500;
  private static final int LAST = 0x257F;
  private static final float CURVE_KAPPA = 0.5522848f;
  private static final BoxGlyph[] GLYPHS = buildGlyphs();

  private final Paint hardEdgePaint = new Paint();
  private final Paint antiAliasPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();

  BoxDrawingGlyphPainter() {
    antiAliasPaint.setStyle(Paint.Style.STROKE);
    antiAliasPaint.setStrokeCap(Paint.Cap.BUTT);
    antiAliasPaint.setStrokeJoin(Paint.Join.MITER);
  }

  static boolean supports(int codePoint) {
    return codePoint >= FIRST && codePoint <= LAST;
  }

  static boolean hasDescriptor(int codePoint) {
    return supports(codePoint) && GLYPHS[codePoint - FIRST] != null;
  }

  boolean draw(Canvas canvas, int codePoint, int left, int top, int right, int bottom,
               int foreground) {
    return draw(canvas, codePoint, left, top, right, bottom, foreground, 0, 0, 0,
        right - left);
  }

  boolean draw(Canvas canvas, int codePoint, int left, int top, int right, int bottom,
               int foreground, int phaseX, int phaseY) {
    return draw(canvas, codePoint, left, top, right, bottom, foreground,
        phaseX, phaseY, 0, right - left);
  }

  boolean draw(Canvas canvas, int codePoint, int left, int top, int right, int bottom,
               int foreground, int phaseX, int phaseY, int column, float nominalCellWidth) {
    if (!supports(codePoint) || left >= right || top >= bottom) return false;
    BoxGlyph glyph = GLYPHS[codePoint - FIRST];
    if (glyph == null) return false;

    hardEdgePaint.setColor(foreground);
    int width = right - left;
    int height = bottom - top;
    int minDimension = Math.max(1, Math.min(width, height));
    int lightWidth = Math.max(1, Math.round(minDimension * 0.08f));
    int heavyWidth = Math.max(lightWidth + 1, Math.round(minDimension * 0.16f));
    int centerX = left + width / 2;
    int centerY = top + height / 2;

    if (glyph.shape == Shape.DIAGONAL_FORWARD
        || glyph.shape == Shape.DIAGONAL_BACKWARD
        || glyph.shape == Shape.DIAGONAL_CROSS) {
      drawDiagonal(canvas, glyph.shape, left, top, right, bottom, lightWidth, foreground);
      return true;
    }
    if (glyph.shape == Shape.ROUNDED) {
      drawRoundedCorner(canvas, glyph, left, top, right, bottom, lightWidth, foreground);
      return true;
    }

    float logicalCellLeft = column * nominalCellWidth;
    drawHorizontalDirection(canvas, left, centerX, left, right, logicalCellLeft,
        nominalCellWidth, centerY, glyph.left, glyph.horizontalPattern, lightWidth,
        heavyWidth);
    drawHorizontalDirection(canvas, centerX, right, left, right, logicalCellLeft,
        nominalCellWidth, centerY, glyph.right, glyph.horizontalPattern, lightWidth,
        heavyWidth);
    drawVerticalDirection(canvas, top, centerY, top, bottom, centerX, glyph.top,
        glyph.verticalPattern, lightWidth, heavyWidth, phaseY);
    drawVerticalDirection(canvas, centerY, bottom, top, bottom, centerX, glyph.bottom,
        glyph.verticalPattern, lightWidth, heavyWidth, phaseY);

    // Two parallel double lines are intentionally separated on straight glyphs. For corners
    // and junctions fill only the central hub so mixed double/single connections do not leave a
    // transparent hole at the join.
    if (needsHub(glyph)) {
      int hubWidth = Math.max(lightWidth, Math.min(heavyWidth, minDimension / 3));
      int hubLeft = bandStart(centerX, hubWidth);
      int hubTop = bandStart(centerY, hubWidth);
      hardEdgePaint.setStyle(Paint.Style.FILL);
      canvas.drawRect(hubLeft, hubTop, bandEnd(centerX, hubWidth),
          bandEnd(centerY, hubWidth), hardEdgePaint);
    }
    return true;
  }

  private static boolean needsHub(BoxGlyph glyph) {
    int connections = countConnections(glyph);
    if (connections < 2) return false;
    boolean horizontalStraight = glyph.left != Stroke.NONE && glyph.right != Stroke.NONE
        && glyph.top == Stroke.NONE && glyph.bottom == Stroke.NONE;
    boolean verticalStraight = glyph.top != Stroke.NONE && glyph.bottom != Stroke.NONE
        && glyph.left == Stroke.NONE && glyph.right == Stroke.NONE;
    if (horizontalStraight || verticalStraight) return false;
    return glyph.left == Stroke.DOUBLE || glyph.right == Stroke.DOUBLE
        || glyph.top == Stroke.DOUBLE || glyph.bottom == Stroke.DOUBLE;
  }

  private static int countConnections(BoxGlyph glyph) {
    int result = 0;
    if (glyph.left != Stroke.NONE) result++;
    if (glyph.right != Stroke.NONE) result++;
    if (glyph.top != Stroke.NONE) result++;
    if (glyph.bottom != Stroke.NONE) result++;
    return result;
  }

  private void drawHorizontalDirection(Canvas canvas, int start, int end,
                                       int cellLeft, int cellRight, float logicalCellLeft,
                                       float nominalCellWidth, int center,
                                       Stroke stroke, Pattern pattern, int lightWidth,
                                       int heavyWidth) {
    if (stroke == Stroke.NONE || start >= end) return;
    int from = Math.min(start, end);
    int to = Math.max(start, end);
    if (stroke == Stroke.DOUBLE) {
      int offset = Math.max(1, lightWidth);
      drawHorizontalPattern(canvas, from, to, cellLeft, cellRight, logicalCellLeft,
          nominalCellWidth, center - offset, lightWidth, pattern);
      drawHorizontalPattern(canvas, from, to, cellLeft, cellRight, logicalCellLeft,
          nominalCellWidth, center + offset, lightWidth, pattern);
    } else {
      drawHorizontalPattern(canvas, from, to, cellLeft, cellRight, logicalCellLeft,
          nominalCellWidth, center, stroke == Stroke.HEAVY ? heavyWidth : lightWidth, pattern);
    }
  }

  private void drawVerticalDirection(Canvas canvas, int start, int end,
                                     int cellTop, int cellBottom, int center,
                                     Stroke stroke, Pattern pattern, int lightWidth,
                                     int heavyWidth, int phaseY) {
    if (stroke == Stroke.NONE || start >= end) return;
    int from = Math.min(start, end);
    int to = Math.max(start, end);
    if (stroke == Stroke.DOUBLE) {
      int offset = Math.max(1, lightWidth);
      drawVerticalPattern(canvas, from, to, cellTop, cellBottom, center - offset,
          lightWidth, pattern, phaseY);
      drawVerticalPattern(canvas, from, to, cellTop, cellBottom, center + offset,
          lightWidth, pattern, phaseY);
    } else {
      drawVerticalPattern(canvas, from, to, cellTop, cellBottom, center,
          stroke == Stroke.HEAVY ? heavyWidth : lightWidth, pattern, phaseY);
    }
  }

  private void drawHorizontalPattern(Canvas canvas, int left, int right,
                                     int cellLeft, int cellRight, float logicalCellLeft,
                                     float nominalCellWidth, int centerY, int thickness,
                                     Pattern pattern) {
    if (pattern == Pattern.SOLID) {
      hardEdgePaint.setStyle(Paint.Style.FILL);
      canvas.drawRect(left, bandStart(centerY, thickness), right,
          bandEnd(centerY, thickness), hardEdgePaint);
      return;
    }
    float period = dashPeriod(pattern, nominalCellWidth);
    float dashLength = Math.max(1f, period / 2f);
    int first = (int) Math.floor((cellLeft - logicalCellLeft) / period) - 1;
    hardEdgePaint.setStyle(Paint.Style.FILL);
    for (int index = first; ; index++) {
      float start = logicalCellLeft + index * period;
      if (start >= cellRight) break;
      float end = start + dashLength;
      int segmentLeft = Math.max(left, Math.round(start));
      int segmentRight = Math.min(right, Math.round(end));
      if (segmentLeft < segmentRight) {
        canvas.drawRect(segmentLeft, bandStart(centerY, thickness), segmentRight,
            bandEnd(centerY, thickness), hardEdgePaint);
      }
    }
  }

  private void drawVerticalPattern(Canvas canvas, int top, int bottom,
                                   int cellTop, int cellBottom, int centerX,
                                   int thickness, Pattern pattern, int phaseY) {
    if (pattern == Pattern.SOLID) {
      hardEdgePaint.setStyle(Paint.Style.FILL);
      canvas.drawRect(bandStart(centerX, thickness), top,
          bandEnd(centerX, thickness), bottom, hardEdgePaint);
      return;
    }
    float period = dashPeriod(pattern, Math.max(1, cellBottom - cellTop));
    float dashLength = Math.max(1f, period / 2f);
    int first = (int) Math.floor((0f - phaseY) / period) - 1;
    hardEdgePaint.setStyle(Paint.Style.FILL);
    for (int index = first; ; index++) {
      float start = phaseY + index * period;
      if (start >= cellBottom - cellTop) break;
      float end = start + dashLength;
      int segmentTop = Math.max(top, cellTop + Math.round(start));
      int segmentBottom = Math.min(bottom, cellTop + Math.round(end));
      if (segmentTop < segmentBottom) {
        canvas.drawRect(bandStart(centerX, thickness), segmentTop,
            bandEnd(centerX, thickness), segmentBottom, hardEdgePaint);
      }
    }
  }

  private static float dashPeriod(Pattern pattern, float dimension) {
    return Math.max(2f, dimension / pattern.dashCount());
  }

  private void drawRoundedCorner(Canvas canvas, BoxGlyph glyph, int left, int top,
                                 int right, int bottom, int strokeWidth, int foreground) {
    int centerX = left + (right - left) / 2;
    int centerY = top + (bottom - top) / 2;
    antiAliasPaint.setColor(foreground);
    antiAliasPaint.setStrokeWidth(strokeWidth);
    antiAliasPaint.setStyle(Paint.Style.STROKE);
    path.reset();
    float radiusX = (right - left) / 2f;
    float radiusY = (bottom - top) / 2f;
    float kx = radiusX * CURVE_KAPPA;
    float ky = radiusY * CURVE_KAPPA;
    // The curve must be tangent to the vertical stroke at the top/bottom edge and tangent
    // to the horizontal stroke at the left/right edge. The previous control points swapped
    // those axes, producing a deep inward bow and a visible kink where the arc met straight
    // neighbouring cells.
    if (glyph.right != Stroke.NONE && glyph.bottom != Stroke.NONE) {
      path.moveTo(centerX, bottom);
      path.cubicTo(centerX, bottom - ky, right - kx, centerY, right, centerY);
    } else if (glyph.left != Stroke.NONE && glyph.bottom != Stroke.NONE) {
      path.moveTo(centerX, bottom);
      path.cubicTo(centerX, bottom - ky, left + kx, centerY, left, centerY);
    } else if (glyph.left != Stroke.NONE && glyph.top != Stroke.NONE) {
      path.moveTo(centerX, top);
      path.cubicTo(centerX, top + ky, left + kx, centerY, left, centerY);
    } else {
      path.moveTo(centerX, top);
      path.cubicTo(centerX, top + ky, right - kx, centerY, right, centerY);
    }
    canvas.drawPath(path, antiAliasPaint);
  }

  private void drawDiagonal(Canvas canvas, Shape shape, int left, int top, int right, int bottom,
                            int strokeWidth, int foreground) {
    antiAliasPaint.setColor(foreground);
    antiAliasPaint.setStrokeWidth(strokeWidth);
    antiAliasPaint.setStyle(Paint.Style.STROKE);
    path.reset();
    if (shape == Shape.DIAGONAL_FORWARD || shape == Shape.DIAGONAL_CROSS) {
      path.moveTo(left, bottom);
      path.lineTo(right, top);
    }
    if (shape == Shape.DIAGONAL_BACKWARD || shape == Shape.DIAGONAL_CROSS) {
      path.moveTo(left, top);
      path.lineTo(right, bottom);
    }
    canvas.drawPath(path, antiAliasPaint);
  }

  private static int bandStart(int center, int thickness) {
    return center - thickness / 2;
  }

  private static int bandEnd(int center, int thickness) {
    return bandStart(center, thickness) + Math.max(1, thickness);
  }

  private enum Stroke {
    NONE,
    LIGHT,
    HEAVY,
    DOUBLE
  }

  private enum Pattern {
    SOLID,
    DOUBLE_DASH,
    TRIPLE_DASH,
    QUADRUPLE_DASH;

    int dashCount() {
      switch (this) {
        case DOUBLE_DASH: return 2;
        case TRIPLE_DASH: return 3;
        case QUADRUPLE_DASH: return 4;
        case SOLID: return 1;
      }
      return 1;
    }
  }

  private enum Shape {
    NORMAL,
    ROUNDED,
    DIAGONAL_FORWARD,
    DIAGONAL_BACKWARD,
    DIAGONAL_CROSS
  }

  private static final class BoxGlyph {
    final Stroke left;
    final Stroke right;
    final Stroke top;
    final Stroke bottom;
    final Pattern horizontalPattern;
    final Pattern verticalPattern;
    final Shape shape;

    BoxGlyph(Stroke left, Stroke right, Stroke top, Stroke bottom,
             Pattern horizontalPattern, Pattern verticalPattern, Shape shape) {
      this.left = left;
      this.right = right;
      this.top = top;
      this.bottom = bottom;
      this.horizontalPattern = horizontalPattern;
      this.verticalPattern = verticalPattern;
      this.shape = shape;
    }
  }

  private static BoxGlyph glyph(Stroke left, Stroke right, Stroke top, Stroke bottom) {
    return glyph(left, right, top, bottom, Pattern.SOLID, Pattern.SOLID, Shape.NORMAL);
  }

  private static BoxGlyph glyph(Stroke left, Stroke right, Stroke top, Stroke bottom,
                                Pattern horizontalPattern, Pattern verticalPattern) {
    return glyph(left, right, top, bottom, horizontalPattern, verticalPattern, Shape.NORMAL);
  }

  private static BoxGlyph glyph(Stroke left, Stroke right, Stroke top, Stroke bottom,
                                Pattern horizontalPattern, Pattern verticalPattern,
                                Shape shape) {
    return new BoxGlyph(left, right, top, bottom, horizontalPattern, verticalPattern, shape);
  }

  private static void set(BoxGlyph[] glyphs, int codePoint, BoxGlyph glyph) {
    glyphs[codePoint - FIRST] = glyph;
  }

  private static BoxGlyph[] buildGlyphs() {
    BoxGlyph[] glyphs = new BoxGlyph[LAST - FIRST + 1];

    Stroke l = Stroke.LIGHT;
    Stroke h = Stroke.HEAVY;
    Stroke d = Stroke.DOUBLE;
    Stroke n = Stroke.NONE;

    set(glyphs, 0x2500, glyph(l, l, n, n));
    set(glyphs, 0x2501, glyph(h, h, n, n));
    set(glyphs, 0x2502, glyph(n, n, l, l));
    set(glyphs, 0x2503, glyph(n, n, h, h));
    set(glyphs, 0x2504, glyph(l, l, n, n, Pattern.TRIPLE_DASH, Pattern.SOLID));
    set(glyphs, 0x2505, glyph(h, h, n, n, Pattern.TRIPLE_DASH, Pattern.SOLID));
    set(glyphs, 0x2506, glyph(n, n, l, l, Pattern.SOLID, Pattern.TRIPLE_DASH));
    set(glyphs, 0x2507, glyph(n, n, h, h, Pattern.SOLID, Pattern.TRIPLE_DASH));
    set(glyphs, 0x2508, glyph(l, l, n, n, Pattern.QUADRUPLE_DASH, Pattern.SOLID));
    set(glyphs, 0x2509, glyph(h, h, n, n, Pattern.QUADRUPLE_DASH, Pattern.SOLID));
    set(glyphs, 0x250A, glyph(n, n, l, l, Pattern.SOLID, Pattern.QUADRUPLE_DASH));
    set(glyphs, 0x250B, glyph(n, n, h, h, Pattern.SOLID, Pattern.QUADRUPLE_DASH));

    set(glyphs, 0x250C, glyph(n, l, n, l));
    set(glyphs, 0x250D, glyph(n, h, n, l));
    set(glyphs, 0x250E, glyph(n, l, n, h));
    set(glyphs, 0x250F, glyph(n, h, n, h));
    set(glyphs, 0x2510, glyph(l, n, n, l));
    set(glyphs, 0x2511, glyph(h, n, n, l));
    set(glyphs, 0x2512, glyph(l, n, n, h));
    set(glyphs, 0x2513, glyph(h, n, n, h));
    set(glyphs, 0x2514, glyph(n, l, l, n));
    set(glyphs, 0x2515, glyph(n, h, l, n));
    set(glyphs, 0x2516, glyph(n, l, h, n));
    set(glyphs, 0x2517, glyph(n, h, h, n));
    set(glyphs, 0x2518, glyph(l, n, l, n));
    set(glyphs, 0x2519, glyph(h, n, l, n));
    set(glyphs, 0x251A, glyph(l, n, h, n));
    set(glyphs, 0x251B, glyph(h, n, h, n));

    set(glyphs, 0x251C, glyph(n, l, l, l));
    set(glyphs, 0x251D, glyph(n, h, l, l));
    set(glyphs, 0x251E, glyph(n, l, h, l));
    set(glyphs, 0x251F, glyph(n, l, l, h));
    set(glyphs, 0x2520, glyph(n, l, h, h));
    set(glyphs, 0x2521, glyph(n, h, h, l));
    set(glyphs, 0x2522, glyph(n, h, l, h));
    set(glyphs, 0x2523, glyph(n, h, h, h));
    set(glyphs, 0x2524, glyph(l, n, l, l));
    set(glyphs, 0x2525, glyph(h, n, l, l));
    set(glyphs, 0x2526, glyph(l, n, h, l));
    set(glyphs, 0x2527, glyph(l, n, l, h));
    set(glyphs, 0x2528, glyph(l, n, h, h));
    set(glyphs, 0x2529, glyph(h, n, h, l));
    set(glyphs, 0x252A, glyph(h, n, l, h));
    set(glyphs, 0x252B, glyph(h, n, h, h));

    set(glyphs, 0x252C, glyph(l, l, n, l));
    set(glyphs, 0x252D, glyph(h, l, n, l));
    set(glyphs, 0x252E, glyph(l, h, n, l));
    set(glyphs, 0x252F, glyph(h, h, n, l));
    set(glyphs, 0x2530, glyph(l, l, n, h));
    set(glyphs, 0x2531, glyph(h, l, n, h));
    set(glyphs, 0x2532, glyph(l, h, n, h));
    set(glyphs, 0x2533, glyph(h, h, n, h));
    set(glyphs, 0x2534, glyph(l, l, l, n));
    set(glyphs, 0x2535, glyph(h, l, l, n));
    set(glyphs, 0x2536, glyph(l, h, l, n));
    set(glyphs, 0x2537, glyph(h, h, l, n));
    set(glyphs, 0x2538, glyph(l, l, h, n));
    set(glyphs, 0x2539, glyph(h, l, h, n));
    set(glyphs, 0x253A, glyph(l, h, h, n));
    set(glyphs, 0x253B, glyph(h, h, h, n));

    set(glyphs, 0x253C, glyph(l, l, l, l));
    set(glyphs, 0x253D, glyph(h, l, l, l));
    set(glyphs, 0x253E, glyph(l, h, l, l));
    set(glyphs, 0x253F, glyph(h, h, l, l));
    set(glyphs, 0x2540, glyph(l, l, h, l));
    set(glyphs, 0x2541, glyph(l, l, l, h));
    set(glyphs, 0x2542, glyph(l, l, h, h));
    set(glyphs, 0x2543, glyph(h, l, h, l));
    set(glyphs, 0x2544, glyph(l, h, h, l));
    set(glyphs, 0x2545, glyph(h, l, l, h));
    set(glyphs, 0x2546, glyph(l, h, l, h));
    set(glyphs, 0x2547, glyph(h, h, h, l));
    set(glyphs, 0x2548, glyph(h, h, l, h));
    set(glyphs, 0x2549, glyph(h, l, h, h));
    set(glyphs, 0x254A, glyph(l, h, h, h));
    set(glyphs, 0x254B, glyph(h, h, h, h));

    set(glyphs, 0x254C, glyph(l, l, n, n, Pattern.DOUBLE_DASH, Pattern.SOLID));
    set(glyphs, 0x254D, glyph(h, h, n, n, Pattern.DOUBLE_DASH, Pattern.SOLID));
    set(glyphs, 0x254E, glyph(n, n, l, l, Pattern.SOLID, Pattern.DOUBLE_DASH));
    set(glyphs, 0x254F, glyph(n, n, h, h, Pattern.SOLID, Pattern.DOUBLE_DASH));

    set(glyphs, 0x2550, glyph(d, d, n, n));
    set(glyphs, 0x2551, glyph(n, n, d, d));
    set(glyphs, 0x2552, glyph(n, d, n, l));
    set(glyphs, 0x2553, glyph(n, l, n, d));
    set(glyphs, 0x2554, glyph(n, d, n, d));
    set(glyphs, 0x2555, glyph(d, n, n, l));
    set(glyphs, 0x2556, glyph(l, n, n, d));
    set(glyphs, 0x2557, glyph(d, n, n, d));
    set(glyphs, 0x2558, glyph(n, d, l, n));
    set(glyphs, 0x2559, glyph(n, l, d, n));
    set(glyphs, 0x255A, glyph(n, d, d, n));
    set(glyphs, 0x255B, glyph(d, n, l, n));
    set(glyphs, 0x255C, glyph(l, n, d, n));
    set(glyphs, 0x255D, glyph(d, n, d, n));
    set(glyphs, 0x255E, glyph(n, d, l, l));
    set(glyphs, 0x255F, glyph(n, l, d, d));
    set(glyphs, 0x2560, glyph(n, d, d, d));
    set(glyphs, 0x2561, glyph(d, n, l, l));
    set(glyphs, 0x2562, glyph(l, n, d, d));
    set(glyphs, 0x2563, glyph(d, n, d, d));
    set(glyphs, 0x2564, glyph(d, d, n, l));
    set(glyphs, 0x2565, glyph(l, l, n, d));
    set(glyphs, 0x2566, glyph(d, d, n, d));
    set(glyphs, 0x2567, glyph(d, d, l, n));
    set(glyphs, 0x2568, glyph(l, l, d, n));
    set(glyphs, 0x2569, glyph(d, d, d, n));
    set(glyphs, 0x256A, glyph(d, d, l, l));
    set(glyphs, 0x256B, glyph(l, l, d, d));
    set(glyphs, 0x256C, glyph(d, d, d, d));

    set(glyphs, 0x256D, glyph(n, l, n, l, Pattern.SOLID, Pattern.SOLID, Shape.ROUNDED));
    set(glyphs, 0x256E, glyph(l, n, n, l, Pattern.SOLID, Pattern.SOLID, Shape.ROUNDED));
    set(glyphs, 0x256F, glyph(l, n, l, n, Pattern.SOLID, Pattern.SOLID, Shape.ROUNDED));
    set(glyphs, 0x2570, glyph(n, l, l, n, Pattern.SOLID, Pattern.SOLID, Shape.ROUNDED));
    set(glyphs, 0x2571, glyph(n, n, n, n, Pattern.SOLID, Pattern.SOLID,
        Shape.DIAGONAL_FORWARD));
    set(glyphs, 0x2572, glyph(n, n, n, n, Pattern.SOLID, Pattern.SOLID,
        Shape.DIAGONAL_BACKWARD));
    set(glyphs, 0x2573, glyph(n, n, n, n, Pattern.SOLID, Pattern.SOLID,
        Shape.DIAGONAL_CROSS));
    set(glyphs, 0x2574, glyph(l, n, n, n));
    set(glyphs, 0x2575, glyph(n, n, l, n));
    set(glyphs, 0x2576, glyph(n, l, n, n));
    set(glyphs, 0x2577, glyph(n, n, n, l));
    set(glyphs, 0x2578, glyph(h, n, n, n));
    set(glyphs, 0x2579, glyph(n, n, h, n));
    set(glyphs, 0x257A, glyph(n, h, n, n));
    set(glyphs, 0x257B, glyph(n, n, n, h));
    set(glyphs, 0x257C, glyph(l, h, n, n));
    set(glyphs, 0x257D, glyph(n, n, l, h));
    set(glyphs, 0x257E, glyph(h, l, n, n));
    set(glyphs, 0x257F, glyph(n, n, h, l));

    return glyphs;
  }
}
