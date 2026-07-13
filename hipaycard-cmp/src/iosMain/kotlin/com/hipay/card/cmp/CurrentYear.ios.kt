package com.hipay.card.cmp

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate

internal actual fun currentYear(): Int =
    NSCalendar.currentCalendar.components(NSCalendarUnitYear, fromDate = NSDate()).year.toInt()
