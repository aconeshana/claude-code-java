package com.claudecode.ui.lanterna.stats;

import com.claudecode.ui.lanterna.repl.InteractiveSessionPort;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public final class HeatmapRenderer {

    private static final String[] MONTH_NAMES =
        {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    private static final String[] DAY_LABELS =
        {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    private static final char[] INTENSITY_CHARS = {'·', '░', '▒', '▓', '█'};

    private HeatmapRenderer() {}

    /** One heatmap grid cell: display char + intensity level 0–4 (-1 = future/blank). */
    public record Cell(char ch, int intensity) {}

    /** The rendered heatmap: label rows are plain strings; the grid is 7 rows of cells. */
    public record Heatmap(String monthLabelRow, List<String> dayLabels, List<List<Cell>> grid) {}


    public static Heatmap render(List<InteractiveSessionPort.DailyActivity> dailyActivity, int terminalWidth, ZoneId zone) {
        int dayLabelWidth = 4;
        int width = Math.min(52, Math.max(10, terminalWidth - dayLabelWidth));

        Map<String, InteractiveSessionPort.DailyActivity> activityMap = new HashMap<>();
        for (InteractiveSessionPort.DailyActivity a : dailyActivity) activityMap.put(a.date(), a);

        long[] percentiles = calculatePercentiles(dailyActivity);

        LocalDate today = LocalDate.now(zone);
        int dow = today.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : today.getDayOfWeek().getValue();
        LocalDate currentWeekStart = today.minusDays(dow);
        LocalDate startDate = currentWeekStart.minusDays((width - 1) * 7L);

        Cell[][] grid = new Cell[7][width];
        List<int[]> monthStarts = new ArrayList<>();  // {monthIndex, week}
        int lastMonth = -1;

        LocalDate current = startDate;
        for (int week = 0; week < width; week++) {
            for (int day = 0; day < 7; day++) {
                if (current.isAfter(today)) {
                    grid[day][week] = new Cell(' ', -1);
                    current = current.plusDays(1);
                    continue;
                }
                String dateStr = StatsDateDisplay.localMidnightUtcDate(current, zone);
                InteractiveSessionPort.DailyActivity activity = activityMap.get(dateStr);

                if (day == 0) {
                    int month = current.getMonthValue() - 1;
                    if (month != lastMonth) {
                        monthStarts.add(new int[]{month, week});
                        lastMonth = month;
                    }
                }

                int intensity = getIntensity(activity != null ? activity.messageCount() : 0, percentiles);
                grid[day][week] = new Cell(INTENSITY_CHARS[intensity], intensity);
                current = current.plusDays(1);
            }
        }


        StringBuilder monthRow = new StringBuilder("    ");
        int labelWidth = width / Math.max(monthStarts.size(), 1);
        for (int[] m : monthStarts) {
            String name = MONTH_NAMES[m[0]];
            monthRow.append(name);
            for (int i = name.length(); i < labelWidth; i++) monthRow.append(' ');
        }


        List<String> dayLabels = new ArrayList<>(7);
        for (int day = 0; day < 7; day++) {
            String label = (day == 1 || day == 3 || day == 5) ? DAY_LABELS[day] : "   ";
            dayLabels.add(label + " ");
        }

        List<List<Cell>> gridRows = new ArrayList<>(7);
        for (Cell[] row : grid) gridRows.add(List.of(row));
        return new Heatmap(monthRow.toString(), dayLabels, gridRows);
    }


    static long[] calculatePercentiles(List<InteractiveSessionPort.DailyActivity> dailyActivity) {
        List<Long> counts = dailyActivity.stream()
            .map(InteractiveSessionPort.DailyActivity::messageCount)
            .filter(c -> c > 0)
            .sorted()
            .toList();
        if (counts.isEmpty()) return null;
        return new long[]{
            counts.get((int) Math.floor(counts.size() * 0.25)),
            counts.get((int) Math.floor(counts.size() * 0.5)),
            counts.get((int) Math.floor(counts.size() * 0.75))};
    }


    static int getIntensity(long messageCount, long[] percentiles) {
        if (messageCount == 0 || percentiles == null) return 0;
        if (messageCount >= percentiles[2]) return 4;
        if (messageCount >= percentiles[1]) return 3;
        if (messageCount >= percentiles[0]) return 2;
        return 1;
    }
}
