package cn.soul2.imageai.search

import kotlin.math.abs

object DamerauLevenshtein {
    fun maximumDistanceFor(codePointLength: Int): Int? {
        require(codePointLength in 0..SearchLimits.QUERY_CODE_POINTS) {
            "query length must be between 0 and ${SearchLimits.QUERY_CODE_POINTS} code points"
        }
        return when (codePointLength) {
            in 0..2 -> null
            in 3..5 -> 1
            else -> 2
        }
    }

    fun withinDistance(left: String, right: String, maximum: Int): Int? {
        require(maximum in 0..SearchLimits.QUERY_CODE_POINTS) {
            "maximum must be between 0 and ${SearchLimits.QUERY_CODE_POINTS}"
        }
        requireValidSearchUtf16(left, "left")
        requireValidSearchUtf16(right, "right")

        val leftCodePoints = left.codePoints().toArray()
        val rightCodePoints = right.codePoints().toArray()
        if (abs(leftCodePoints.size - rightCodePoints.size) > maximum) return null

        val columns: IntArray
        val rows: IntArray
        if (leftCodePoints.size <= rightCodePoints.size) {
            columns = leftCodePoints
            rows = rightCodePoints
        } else {
            columns = rightCodePoints
            rows = leftCodePoints
        }

        val outOfBand = if (maximum == Int.MAX_VALUE) Int.MAX_VALUE else maximum + 1
        var previousPrevious = IntArray(columns.size + 1) { outOfBand }
        var previous = IntArray(columns.size + 1) { index ->
            if (index <= maximum) index else outOfBand
        }
        var current = IntArray(columns.size + 1) { outOfBand }

        for (row in 1..rows.size) {
            current.fill(outOfBand)
            if (row <= maximum) current[0] = row
            val firstColumn = maxOf(1, row - maximum)
            val lastColumn = minOf(columns.size, row + maximum)
            var rowMinimum = current[0]

            for (column in firstColumn..lastColumn) {
                val substitutionCost = if (rows[row - 1] == columns[column - 1]) 0 else 1
                var distance = minOf(
                    increment(previous[column], outOfBand),
                    increment(current[column - 1], outOfBand),
                    add(previous[column - 1], substitutionCost, outOfBand),
                )
                if (
                    row > 1 &&
                    column > 1 &&
                    rows[row - 1] == columns[column - 2] &&
                    rows[row - 2] == columns[column - 1]
                ) {
                    distance = minOf(distance, increment(previousPrevious[column - 2], outOfBand))
                }
                current[column] = distance
                rowMinimum = minOf(rowMinimum, distance)
            }
            if (rowMinimum > maximum) return null

            val reusable = previousPrevious
            previousPrevious = previous
            previous = current
            current = reusable
        }

        return previous[columns.size].takeIf { it <= maximum }
    }

    private fun increment(value: Int, ceiling: Int): Int = add(value, 1, ceiling)

    private fun add(value: Int, amount: Int, ceiling: Int): Int =
        if (value >= ceiling || value > Int.MAX_VALUE - amount) ceiling else minOf(value + amount, ceiling)
}
