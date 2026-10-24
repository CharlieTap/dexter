package io.github.charlietap.dexter.internal

internal actual fun adler32(
    bytes: ByteArray,
    start: Int,
    end: Int,
): Int {
    var sum = 1
    var sumOfSums = 0
    var at = start
    while (at < end) {
        // 3800 bytes keep sumOfSums below 2^31, so the signed remainder stays correct.
        val chunkEnd = minOf(end, at + 3800)
        // Eight independent lanes: lane k sums byte k of each 8-byte block, and its partner sums the
        // lane's running total before each block, which recombine into sum and sumOfSums at the end.
        var lane0 = 0
        var lane1 = 0
        var lane2 = 0
        var lane3 = 0
        var lane4 = 0
        var lane5 = 0
        var lane6 = 0
        var lane7 = 0
        var laneTotal0 = 0
        var laneTotal1 = 0
        var laneTotal2 = 0
        var laneTotal3 = 0
        var laneTotal4 = 0
        var laneTotal5 = 0
        var laneTotal6 = 0
        var laneTotal7 = 0
        val blocks = (chunkEnd - at) ushr 3
        repeat(blocks) {
            laneTotal0 += lane0
            laneTotal1 += lane1
            laneTotal2 += lane2
            laneTotal3 += lane3
            laneTotal4 += lane4
            laneTotal5 += lane5
            laneTotal6 += lane6
            laneTotal7 += lane7
            lane0 += bytes[at].toInt() and 0xFF
            lane1 += bytes[at + 1].toInt() and 0xFF
            lane2 += bytes[at + 2].toInt() and 0xFF
            lane3 += bytes[at + 3].toInt() and 0xFF
            lane4 += bytes[at + 4].toInt() and 0xFF
            lane5 += bytes[at + 5].toInt() and 0xFF
            lane6 += bytes[at + 6].toInt() and 0xFF
            lane7 += bytes[at + 7].toInt() and 0xFF
            at += 8
        }
        val totals = laneTotal0 + laneTotal1 + laneTotal2 + laneTotal3 + laneTotal4 + laneTotal5 + laneTotal6 + laneTotal7
        sumOfSums += 8 * (blocks * sum + totals) +
            8 * lane0 + 7 * lane1 + 6 * lane2 + 5 * lane3 + 4 * lane4 + 3 * lane5 + 2 * lane6 + lane7
        sum += lane0 + lane1 + lane2 + lane3 + lane4 + lane5 + lane6 + lane7
        while (at < chunkEnd) {
            sum += bytes[at].toInt() and 0xFF
            sumOfSums += sum
            at++
        }
        sum %= 65521
        sumOfSums %= 65521
    }
    return (sumOfSums shl 16) or sum
}
