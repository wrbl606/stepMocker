package xyz.wrbl.stepMocker

import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

class StepGeneratorTest {
    private val end = Instant.parse("2025-05-10T12:00:00Z")
    private val zone = ZoneId.of("Europe/Warsaw")
    private fun period(hours: Long) = Period(end.minus(Duration.ofHours(hours)), end)

    @Test fun `preserves exact total and temporal bounds across seeds and densities`() {
        for (hours in listOf(1L, 12L, 24L, 72L, 168L)) {
            for (count in listOf(1L, 10L, 100L, 1000L, hours * 7000L)) {
                if (count > 1_000_000) continue
                repeat(10) { seed ->
                    val range = period(hours)
                    val batches = StepGenerator.generate(range, count, zone, Random(seed))
                    assertEquals(count, batches.sumOf { it.count })
                    batches.forEach {
                        assertTrue(it.start >= range.start && it.end <= range.end)
                        assertTrue(it.start < it.end && it.count > 0)
                        assertTrue(it.count <= Duration.between(it.start, it.end).seconds * 2)
                    }
                    batches.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start) }
                }
            }
        }
    }
    @Test fun `varies sources recording methods durations and counts`() {
        val batches = StepGenerator.generate(period(24), 8000, zone, Random(123))
        assertEquals(5, batches.map { it.device }.distinct().size)
        assertEquals(3, batches.map { it.method }.distinct().size)
        assertTrue(batches.map { it.count }.distinct().size > 10)
        assertTrue(batches.map { Duration.between(it.start, it.end).seconds }.distinct().size > 10)
    }
    @Test fun `sparse activity favours daytime`() {
        val batches = StepGenerator.generate(period(168), 8000, zone, Random(15))
        val night = batches.count { it.start.atZone(zone).hour in 0..5 }
        assertTrue(night < batches.size / 5)
    }
    @Test fun `bucket thresholds use exact duration`() {
        listOf(12L to 1L, 24L to 2L, 72L to 6L, 73L to 12L).forEach { (h, expected) -> assertEquals(expected, period(h).bucketHours) }
        assertEquals(2, Period(end.minus(Duration.ofHours(12)).minusSeconds(1), end).bucketHours)
        assertEquals(6, Period(end.minus(Duration.ofHours(24)).minusSeconds(1), end).bucketHours)
        assertEquals(12, Period(end.minus(Duration.ofHours(72)).minusSeconds(1), end).bucketHours)
    }
    @Test fun `supports a one minute period and exact maximum cadence`() {
        val p = Period(end.minusSeconds(60), end)
        assertEquals(120L, StepGenerator.generate(p, 120, zone, Random(1)).sumOf { it.count })
    }
    @Test fun `handles daylight saving transitions with elapsed time`() {
        val p = Period(Instant.parse("2025-03-29T12:00:00Z"), Instant.parse("2025-03-30T12:00:00Z"))
        val batches = StepGenerator.generate(p, 5000, zone, Random(9))
        assertEquals(5000L, batches.sumOf { it.count })
        assertTrue(batches.all { it.start >= p.start && it.end <= p.end })
        assertEquals(2L, p.bucketHours)
    }
    @Test fun `requested portions preserve totals and bounds across pagination thresholds`() {
        for (portions in listOf(1, 999, 1000, 1001, 2001, 5001, 10000)) {
            repeat(5) { seed ->
                val range = period(24)
                val steps = maxOf(8000L, portions.toLong())
                val batches = StepGenerator.generate(range, steps, zone, Random(seed), portions)
                assertEquals(portions, batches.size)
                assertEquals(steps, batches.sumOf { it.count })
                batches.forEach {
                    assertTrue(it.count > 0)
                    assertTrue(it.start >= range.start && it.end <= range.end && it.start < it.end)
                    assertTrue(it.count <= Duration.between(it.start, it.end).seconds * 2)
                }
                batches.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start) }
            }
        }
    }

    @Test fun `rejects invalid and unachievable portions`() {
        for (portions in listOf(-1, 0, 10001, 101)) {
            assertThrows(IllegalArgumentException::class.java) {
                StepGenerator.generate(period(24), 100, zone, Random(1), portions)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            StepGenerator.generate(Period(end.minusSeconds(60), end), 100, zone, Random(1), 100)
        }
    }

    @Test fun `every requested device contributes without exceeding record count`() {
        for (devices in 1..5) {
            val batches = StepGenerator.generate(period(24), 8000, zone, Random(7), portions = 1001, deviceCount = devices)
            assertEquals(devices, batches.map { it.device }.distinct().size)
            assertEquals(8000L, batches.sumOf { it.count })
        }
        val small = StepGenerator.generate(period(24), 10, zone, Random(7), portions = 2, deviceCount = 5)
        assertEquals(2, small.map { it.device }.distinct().size)
        for (invalid in listOf(0, 6)) {
            assertThrows(IllegalArgumentException::class.java) {
                StepGenerator.generate(period(24), 8000, zone, deviceCount = invalid)
            }
        }
    }

    @Test fun `rejects invalid input`() {
        for ((p, count) in listOf(period(24) to 0L, period(24) to -5L, period(1) to 7201L,
            period(24) to 1_000_001L, Period(end, end) to 1L,
            Period(end, end.minusSeconds(60)) to 1L, period(91 * 24) to 1L,
            Period(Instant.now(), Instant.now().plusSeconds(3600)) to 1L)) {
            assertThrows(IllegalArgumentException::class.java) { StepGenerator.generate(p, count, zone, Random(1)) }
        }
    }
}
