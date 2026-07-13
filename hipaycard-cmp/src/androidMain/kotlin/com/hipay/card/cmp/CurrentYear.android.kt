package com.hipay.card.cmp

import java.util.Calendar

internal actual fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)
