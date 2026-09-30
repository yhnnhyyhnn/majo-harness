package io.majo.harness.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/**
 * The Vixie five-field parser (dsh cron parity): field grammar, name forms,
 * the dom/dow OR rule, timezone evaluation, and loud rejection of the
 * six-field (seconds) form majo does not support.
 */
class CronExpressionTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static ZonedDateTime next(String expression, String afterIso) {
        return CronExpression.parse(expression)
                .next(java.time.ZonedDateTime.parse(afterIso).toInstant(), UTC)
                .atZone(UTC);
    }

    @Test
    void everyMinuteStepsForwardOneMinute() {
        ZonedDateTime next = next("* * * * *", "2026-09-30T10:15:03Z[UTC]");
        assertThat(next).isEqualTo(ZonedDateTime.parse("2026-09-30T10:16Z[UTC]"));
    }

    @Test
    void restrictedFieldsJumpForward() {
        // 09:30 on weekdays
        ZonedDateTime friday = next("30 9 * * 1-5", "2026-09-30T10:00Z[UTC]"); // Wed
        assertThat(friday.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
        assertThat(friday.getHour()).isEqualTo(9);
        assertThat(friday.getMinute()).isEqualTo(30);
        assertThat(friday.toLocalDate().toString()).isEqualTo("2026-10-01");

        // monthly: next occurrence of "0 0 1 * *" after Jan 15 is Feb 1
        ZonedDateTime first = next("0 0 1 * *", "2026-01-15T00:00Z[UTC]");
        assertThat(first.toLocalDate().toString()).isEqualTo("2026-02-01");
    }

    @Test
    void domAndDowBothRestrictedMeansEither() {
        // Friday the 13th: dom=13 restricted AND dow=Friday restricted → either matches
        ZonedDateTime hit = next("0 0 13 * 5", "2026-01-01T00:00Z[UTC]");
        // Jan 13 2026 is a Tuesday; the first match is Friday Jan 2? No: after Jan 1,
        // Fridays Jan 2/9/16... and the 13th (Tuesday). Friday Jan 2 comes first.
        assertThat(hit.toLocalDate().toString()).isEqualTo("2026-01-02");
        // from Jan 13 10:00, the next "13th or Friday" is Friday Jan 16
        ZonedDateTime later = next("0 0 13 * 5", "2026-01-13T10:00Z[UTC]");
        assertThat(later.toLocalDate().toString()).isEqualTo("2026-01-16");
        assertThat(later.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
    }

    @Test
    void namesStepsAndSevenAsSunday() {
        assertThat(next("0 0 1 jan *", "2025-12-01T00:00Z[UTC]").toLocalDate().toString())
                .isEqualTo("2026-01-01");
        // */15 in minutes from 10:07 → 10:15
        assertThat(next("*/15 * * * *", "2026-03-01T10:07Z[UTC]").getMinute()).isEqualTo(15);
        // dow 7 is Sunday, same as 0
        ZonedDateTime viaSeven = next("0 12 * * 7", "2026-03-02T00:00Z[UTC]");
        assertThat(viaSeven.getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
    }

    @Test
    void evaluatesInTheExplicitTimezone() {
        // 09:00 Berlin == 07:00/08:00 UTC depending on DST; pick a stable winter date
        ZonedDateTime next = CronExpression.parse("0 9 * * *")
                .next(java.time.Instant.parse("2026-01-15T10:00:00Z"), ZoneId.of("Europe/Berlin"))
                .atZone(ZoneId.of("Europe/Berlin"));
        assertThat(next.getHour()).isEqualTo(9);
        assertThat(next.getZone()).isEqualTo(ZoneId.of("Europe/Berlin"));
        assertThat(next.toInstant()).isEqualTo(java.time.Instant.parse("2026-01-16T08:00:00Z"));
    }

    @Test
    void februaryTwentyNinthIsReachable() {
        // 2026..2029 has no Feb 29 until 2028 — the four-year scan must find it
        ZonedDateTime leap = next("0 0 29 2 *", "2026-01-01T00:00Z[UTC]");
        assertThat(leap.toLocalDate().toString()).isEqualTo("2028-02-29");
    }

    @Test
    void rejectsMalformedExpressionsLoudly() {
        assertThatThrownBy(() -> CronExpression.parse("0 9 * * * 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 fields");
        assertThatThrownBy(() -> CronExpression.parse("61 * * * *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
        assertThatThrownBy(() -> CronExpression.parse("* 9-5 * * *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reversed");
        assertThatThrownBy(() -> CronExpression.parse("* * * * */x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad step");
        assertThatThrownBy(() -> CronExpression.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zoneValidationLoudAndForgiving() {
        // sanity guard for the zone path used by the tools
        assertThat(CronExpression.zone("Asia/Shanghai")).isEqualTo(ZoneId.of("Asia/Shanghai"));
        assertThatThrownBy(() -> CronExpression.zone("Mars/Olympus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mars/Olympus");
        // null/blank falls back to the host zone
        assertThat(CronExpression.zone(null)).isEqualTo(ZoneId.systemDefault());
        assertThat(CronExpression.zone("   ")).isEqualTo(ZoneId.systemDefault());
    }
}
