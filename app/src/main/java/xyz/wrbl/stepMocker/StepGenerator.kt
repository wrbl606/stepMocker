package xyz.wrbl.stepMocker

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.min
import kotlin.random.Random

data class Period(val start: Instant, val end: Instant) {
    companion object {
        fun today(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Period =
            Period(now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant(), now)
    }

    val duration: Duration get() = Duration.between(start, end)
    val bucketHours: Long get() = when {
        duration <= Duration.ofHours(12) -> 1
        duration <= Duration.ofHours(24) -> 2
        duration <= Duration.ofHours(72) -> 6
        else -> 12
    }
    fun validate(now: Instant = Instant.now()) {
        require(duration >= Duration.ofMinutes(1)) { "Choose a period of at least one minute." }
        require(duration <= Duration.ofDays(90)) { "Choose a period of 90 days or less." }
        require(end <= now) { "The period must end in the past." }
    }
}

enum class Method { AUTOMATIC, ACTIVE, MANUAL }
data class SimDevice(val manufacturer: String, val model: String, val type: String)
data class StepBatch(val start: Instant, val end: Instant, val count: Long, val device: SimDevice, val method: Method)

object StepGenerator {
    val devices = listOf(
        SimDevice("Google", "Pixel 9", "Phone"),
        SimDevice("Google", "Pixel Watch 3", "Watch"),
        SimDevice("Samsung", "Galaxy Watch7", "Watch"),
        SimDevice("Garmin", "Venu 3", "Watch"),
        SimDevice("Fitbit", "Charge 6", "Fitness band"),
    )

    // One non-overlapping slot per batch prevents artificial double counting within a run.
    // Weighted sampling favours waking hours while retaining occasional night activity.
    fun generate(period: Period, steps: Long, zone: ZoneId, random: Random = Random.Default, portions: Int? = null, deviceCount: Int = devices.size): List<StepBatch> {
        period.validate()
        require(deviceCount in 1..devices.size) { "Choose between 1 and ${devices.size} devices." }
        require(steps in 1..1_000_000) { "Enter between 1 and 1,000,000 steps." }
        val seconds = period.duration.seconds
        require(steps <= seconds * 2) { "Too many steps for this period (maximum 120 steps/minute)." }
        val defaultSlots = (seconds / 300).toInt().coerceAtLeast(1)
        val slots = if (portions == null) defaultSlots else {
            require(portions in 1..10_000) { "Enter between 1 and 10,000 portions, or leave blank for automatic." }
            require(portions <= steps) { "Each portion needs at least one step. Reduce portions or add more steps." }
            // Shrink the scheduling slots for pagination-sized datasets, while retaining
            // enough capacity per slot to preserve the exact total and cadence limit.
            val stepsPerSlot = (steps + portions - 1) / portions
            val minimumSlotSeconds = (stepsPerSlot + 1) / 2
            val maxSlots = (seconds / minimumSlotSeconds).toInt()
            require(maxSlots >= portions) { "This many portions cannot fit at a realistic cadence. Choose a longer period or fewer steps/portions." }
            maxOf(defaultSlots, portions * 3).coerceAtMost(maxSlots)
        }
        val capacity = (seconds / slots * 2).toInt()
        require(steps <= slots.toLong() * capacity) { "Reduce the step count slightly to fit this period." }
        val batchCount = portions ?: maxOf(ceil(steps / 85.0).toInt(), ceil(steps.toDouble() / capacity).toInt())
            .coerceAtMost(slots).coerceAtMost(steps.toInt())
        val contributingDevices = devices.shuffled(random).take(minOf(deviceCount, batchCount))
        val slotLength = seconds.toDouble() / slots
        val chosen = (0 until slots).map { slot ->
            val hour = period.start.plusSeconds((slot * slotLength).toLong()).atZone(zone).hour
            val weight = when (hour) { in 0..5 -> 0.04; in 6..8, in 12..13, in 17..19 -> 1.0; else -> 0.45 }
            slot to (-kotlin.math.ln(random.nextDouble().coerceAtLeast(0.000001)) / weight)
        }.sortedBy { it.second }.take(batchCount).map { it.first }.sorted()
        var remaining = steps
        return chosen.mapIndexed { index, slot ->
            val left = batchCount - index - 1
            val low = maxOf(1L, remaining - left.toLong() * capacity)
            val high = min(capacity.toLong(), remaining - left)
            val mean = remaining.toDouble() / (left + 1)
            val count = if (low == high) low else (mean * random.nextDouble(0.25, 1.75)).toLong().coerceIn(low, high)
            remaining -= count
            val slotStart = (slot * slotLength).toLong()
            val slotEnd = ((slot + 1) * slotLength).toLong().coerceAtMost(seconds)
            val minDuration = (count + 1) / 2
            val maxDuration = min(slotEnd - slotStart, maxOf(minDuration, count * 6)).coerceAtLeast(minDuration)
            val duration = random.nextLong(minDuration, maxDuration + 1)
            val offset = random.nextLong(0, slotEnd - slotStart - duration + 1)
            val start = period.start.plusSeconds(slotStart + offset)
            // Include every contributing device at least once, then vary the source randomly.
            val device = contributingDevices.getOrNull(index) ?: contributingDevices.random(random)
            StepBatch(start, start.plusSeconds(duration), count, device,
                when (random.nextInt(100)) { in 0..74 -> Method.AUTOMATIC; in 75..94 -> Method.ACTIVE; else -> Method.MANUAL })
        }
    }
}
