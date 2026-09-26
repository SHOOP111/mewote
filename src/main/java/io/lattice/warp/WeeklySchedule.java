package io.lattice.warp;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Weekly half-open local-time window, interpreted only in the explicitly declared zone. */
public record WeeklySchedule(ZoneId zone, Set<DayOfWeek> days, LocalTime start, LocalTime end) {
    public WeeklySchedule {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(days, "days");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        TreeSet<DayOfWeek> ordered = new TreeSet<>(days);
        if (ordered.isEmpty()) throw new IllegalArgumentException("Schedule requires at least one day");
        if (start.equals(end)) throw new IllegalArgumentException("Schedule start and end cannot be equal");
        days = Set.copyOf(ordered);
    }

    public boolean includes(Instant instant) {
        var local = instant.atZone(zone);
        LocalTime time = local.toLocalTime();
        DayOfWeek day = local.getDayOfWeek();
        if (start.isBefore(end)) {
            return days.contains(day) && !time.isBefore(start) && time.isBefore(end);
        }
        if (!time.isBefore(start)) return days.contains(day);
        if (time.isBefore(end)) return days.contains(day.minus(1));
        return false;
    }
}
