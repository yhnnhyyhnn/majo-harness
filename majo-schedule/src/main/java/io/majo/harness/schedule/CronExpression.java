package io.majo.harness.schedule;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.BitSet;
import java.util.Locale;

/**
 * A Vixie-cron five-field expression (dsh schedule parity): minute hour
 * day-of-month month day-of-week, each supporting {@code *}, lists, ranges,
 * and {@code /step}. Month and day-of-week accept three-letter names
 * ({@code jan-dec}, {@code sun-sat}); day-of-week runs 0-6 with 0 (and 7)
 * as Sunday. Six-field (seconds) expressions are rejected loudly.
 *
 * <p>The Vixie day rule: when both day-of-month and day-of-week are
 * restricted, a day matches if <em>either</em> matches; otherwise the
 * restricted field decides.
 */
public final class CronExpression {

    private static final String[] MONTH_NAMES =
            {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
    private static final String[] DOW_NAMES = {"sun", "mon", "tue", "wed", "thu", "fri", "sat"};

    private final String expression;
    private final BitSet minutes = new BitSet(60);
    private final BitSet hours = new BitSet(24);
    private final BitSet daysOfMonth = new BitSet(32); // 1-based, index 0 unused
    private final BitSet months = new BitSet(13);      // 1-based, index 0 unused
    private final BitSet daysOfWeek = new BitSet(8);   // vixie 0-7, 0 and 7 = Sunday
    private final boolean domRestricted;
    private final boolean dowRestricted;

    private CronExpression(String expression) {
        this.expression = expression;
        String[] fields = expression.trim().split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException("cron: expected 5 fields (minute hour "
                    + "day-of-month month day-of-week), got " + fields.length
                    + " in \"" + expression + "\"");
        }
        parseField(fields[0], 0, 59, null, minutes);
        parseField(fields[1], 0, 23, null, hours);
        domRestricted = !isWildcard(parseField(fields[2], 1, 31, null, daysOfMonth));
        parseField(fields[3], 1, 12, MONTH_NAMES, months);
        dowRestricted = !isWildcard(parseField(fields[4], 0, 7, DOW_NAMES, daysOfWeek));
        // vixie: both 0 and 7 mean Sunday
        if (daysOfWeek.get(7)) {
            daysOfWeek.set(0);
        }
    }

    /** Parses a five-field expression; failures carry the offending field. */
    public static CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("cron: expression must not be blank");
        }
        return new CronExpression(expression);
    }

    public String expression() {
        return expression;
    }

    /** The first occurrence strictly after {@code after}, evaluated in {@code zone}. */
    public java.time.Instant next(java.time.Instant after, ZoneId zone) {
        ZonedDateTime candidate = after.atZone(zone)
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                .plusMinutes(1);
        // four years covers every calendar shape including Feb 29
        ZonedDateTime limit = candidate.plusYears(4);
        while (candidate.isBefore(limit)) {
            if (!months.get(candidate.getMonthValue())) {
                candidate = candidate.plusMonths(1).withDayOfMonth(1)
                        .toLocalDate().atStartOfDay(candidate.getZone());
                continue;
            }
            if (!dayMatches(candidate)) {
                candidate = candidate.plusDays(1)
                        .toLocalDate().atStartOfDay(candidate.getZone());
                continue;
            }
            if (!hours.get(candidate.getHour())) {
                candidate = candidate.plusHours(1)
                        .withMinute(0).withSecond(0).withNano(0);
                continue;
            }
            if (minutes.get(candidate.getMinute())) {
                return candidate.toInstant();
            }
            candidate = candidate.plusMinutes(1);
        }
        throw new IllegalStateException(
                "cron: no occurrence of \"" + expression + "\" within four years");
    }

    /** Vixie day rule: both restricted → either matches; else the restricted one. */
    private boolean dayMatches(ZonedDateTime candidate) {
        boolean dom = daysOfMonth.get(candidate.getDayOfMonth());
        int vixieDow = vixieDayOfWeek(candidate.getDayOfWeek());
        boolean dow = daysOfWeek.get(vixieDow);
        if (domRestricted && dowRestricted) {
            return dom || dow;
        }
        if (domRestricted) {
            return dom;
        }
        if (dowRestricted) {
            return dow;
        }
        return true;
    }

    private static int vixieDayOfWeek(DayOfWeek day) {
        return day.getValue() % 7; // MON=1..SAT=6, SUN=7 → 0
    }

    private static boolean isWildcard(String raw) {
        return raw.equals("*") || raw.startsWith("*/");
    }

    /** Parses one field into the bit set; returns the raw text for wildcard checks. */
    private static String parseField(String raw, int min, int max, String[] names, BitSet target) {
        String text = raw.toLowerCase(Locale.ROOT);
        for (String part : text.split(",")) {
            long step = 1;
            String range = part;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                range = part.substring(0, slash);
                String stepText = part.substring(slash + 1);
                if (!stepText.matches("\\d+") || Long.parseLong(stepText) < 1) {
                    throw new IllegalArgumentException("cron: bad step in field \"" + raw + "\"");
                }
                step = Long.parseLong(stepText);
            }
            int from;
            int to;
            if (range.equals("*")) {
                from = min;
                to = max;
            } else if (range.contains("-")) {
                String[] bounds = range.split("-", 2);
                from = resolve(bounds[0], min, max, names, raw);
                to = resolve(bounds[1], min, max, names, raw);
            } else {
                from = resolve(range, min, max, names, raw);
                to = max;
                if (slash < 0) {
                    target.set(normalize(from, min, max, names));
                    continue;
                }
            }
            if (from > to) {
                throw new IllegalArgumentException(
                        "cron: reversed range in field \"" + raw + "\"");
            }
            for (int value = from; value <= to; value += step) {
                target.set(normalize(value, min, max, names));
            }
        }
        return raw;
    }

    private static int resolve(String text, int min, int max, String[] names, String field) {
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(text)) {
                    return min + i;
                }
            }
        }
        if (!text.matches("\\d+")) {
            throw new IllegalArgumentException("cron: bad value \"" + text + "\" in field \"" + field + "\"");
        }
        return Integer.parseInt(text);
    }

    /** Maps dow 7 → 0 (Sunday); everything else is used as parsed. */
    private static int normalize(int value, int min, int max, String[] names) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    "cron: value " + value + " out of range " + min + "-" + max
                            + (names == null ? "" : " (or use " + names[0] + "-" + names[names.length - 1] + ")"));
        }
        if (names == DOW_NAMES && value == 7) {
            return 0;
        }
        return value;
    }

    /** Validates a zone id for the {@code timezone} argument (loud on typos). */
    public static ZoneId zone(String timezoneId) {
        try {
            return ZoneId.of(timezoneId == null || timezoneId.isBlank()
                    ? ZoneId.systemDefault().getId() : timezoneId);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("cron: unknown timezone \""
                    + timezoneId + "\"");
        }
    }
}
