package io.github.charlietap.dexter.internal

private const val MOD = 65521
private const val LOW_BYTES = 0x00FF_00FF_00FF_00FFL

private const val GROUP_WORDS = 23

private const val GROUPS_PER_REDUCTION = 20

internal actual fun adler32(
    bytes: ByteArray,
    start: Int,
    end: Int,
): Int {
    var sum = 1
    var sumOfSums = 0
    var at = start
    var groups = 0
    while (end - at >= GROUP_WORDS * 8) {
        var evens = 0L
        var odds = 0L
        var evenTotals = 0L
        var oddTotals = 0L
        repeat(GROUP_WORDS) {
            val word = bytes.longAt(at)
            evenTotals += evens
            oddTotals += odds
            evens += word and LOW_BYTES
            odds += (word ushr 8) and LOW_BYTES
            at += 8
        }
        sumOfSums += 8 * (GROUP_WORDS * sum + lanes(evenTotals) + lanes(oddTotals)) + weighted(evens, odds)
        sum += lanes(evens) + lanes(odds)
        if (++groups == GROUPS_PER_REDUCTION) {
            sumOfSums %= MOD
            sum %= MOD
            groups = 0
        }
    }
    sumOfSums %= MOD
    sum %= MOD
    while (at < end) {
        sum += bytes[at].toInt() and 0xFF
        sumOfSums += sum
        at++
    }
    return ((sumOfSums % MOD) shl 16) or (sum % MOD)
}

private fun lanes(
    word: Long,
): Int = lane(word, 0) + lane(word, 1) + lane(word, 2) + lane(word, 3)

private fun weighted(
    evens: Long,
    odds: Long,
): Int =
    8 * lane(evens, 0) + 7 * lane(odds, 0) + 6 * lane(evens, 1) + 5 * lane(odds, 1) +
        4 * lane(evens, 2) + 3 * lane(odds, 2) + 2 * lane(evens, 3) + lane(odds, 3)

private fun lane(
    word: Long,
    index: Int,
): Int = (word ushr (index * 16)).toInt() and 0xFFFF
