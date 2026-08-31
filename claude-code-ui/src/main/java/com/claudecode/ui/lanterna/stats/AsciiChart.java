package com.claudecode.ui.lanterna.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;


public final class AsciiChart {

    /** {@code [ '┼', '┤', '╶', '╴', '─', '╰', '╭', '╮', '╯', '│' ]} */
    private static final String[] SYMBOLS =
        {"┼", "┤", "╶", "╴", "─", "╰", "╭", "╮", "╯", "│"};
    private static final int OFFSET = 3;  // library default

    private AsciiChart() {}

    /** One grid cell: text plus the series that painted it (null = axis/label). */
    public record Cell(String text, Integer seriesIndex) {}

    /**
     * Plots {@code series} into a grid of rows × cells.
     */
    public static List<List<Cell>> plot(List<double[]> series, int height, LongFunction<String> format) {
        double min = series.getFirst()[0];
        double max = series.getFirst()[0];
        for (double[] s : series) {
            for (double v : s) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }

        double range = Math.abs(max - min);
        double ratio = range != 0 ? height / range : 1;
        long min2 = Math.round(min * ratio);
        long max2 = Math.round(max * ratio);
        int rows = (int) Math.abs(max2 - min2);
        int width = 0;
        for (double[] s : series) width = Math.max(width, s.length);
        width += OFFSET;

        Cell[][] result = new Cell[rows + 1][width];
        for (Cell[] row : result) {
            for (int j = 0; j < width; j++) row[j] = new Cell(" ", null);
        }

        // Axis + labels.
        for (long y = min2; y <= max2; y++) {
            double labelValue = rows > 0 ? max - (y - min2) * range / rows : y;
            String label = format.apply(Math.round(labelValue));
            int r = (int) (y - min2);
            result[r][Math.max(OFFSET - label.length(), 0)] = new Cell(label, null);
            result[r][OFFSET - 1] = new Cell(y == 0 ? SYMBOLS[0] : SYMBOLS[1], null);
        }

        // Series lines.
        for (int j = 0; j < series.size(); j++) {
            double[] s = series.get(j);
            int first = (int) (Math.round(s[0] * ratio) - min2);
            result[rows - first][OFFSET - 1] = new Cell(SYMBOLS[0], j);

            for (int x = 0; x < s.length - 1; x++) {
                int y0 = (int) (Math.round(s[x] * ratio) - min2);
                int y1 = (int) (Math.round(s[x + 1] * ratio) - min2);
                if (y0 == y1) {
                    result[rows - y0][x + OFFSET] = new Cell(SYMBOLS[4], j);
                } else {
                    result[rows - y1][x + OFFSET] = new Cell(y0 > y1 ? SYMBOLS[5] : SYMBOLS[6], j);
                    result[rows - y0][x + OFFSET] = new Cell(y0 > y1 ? SYMBOLS[7] : SYMBOLS[8], j);
                    int from = Math.min(y0, y1);
                    int to = Math.max(y0, y1);
                    for (int y = from + 1; y < to; y++) {
                        result[rows - y][x + OFFSET] = new Cell(SYMBOLS[9], j);
                    }
                }
            }
        }

        List<List<Cell>> out = new ArrayList<>(rows + 1);
        for (Cell[] row : result) out.add(List.of(row));
        return out;
    }
}
