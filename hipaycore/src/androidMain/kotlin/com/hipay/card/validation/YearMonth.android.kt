package com.hipay.card.validation

import java.util.Calendar

internal actual fun currentYearMonth(): Pair<Int, Int> {
    val calendar = Calendar.getInstance()
    return calendar.get(Calendar.YEAR) to calendar.get(Calendar.MONTH) + 1
}
