package com.tlisonbee.remind.shared

fun unitName(unit: RepeatUnit, count: Int): String {
    val name = when (unit) {
        RepeatUnit.NONE -> return ""
        RepeatUnit.DAY -> "day"
        RepeatUnit.WEEK -> "week"
        RepeatUnit.MONTH -> "month"
        RepeatUnit.YEAR -> "year"
    }
    return if (count == 1) name else "${name}s"
}

/** e.g. "Every week", "Every 2 months", "Does not repeat". */
fun repeatLabel(unit: RepeatUnit, interval: Int): String = when {
    unit == RepeatUnit.NONE -> "Does not repeat"
    interval == 1 -> "Every ${unitName(unit, 1)}"
    else -> "Every $interval ${unitName(unit, interval)}"
}

/** Short names for a repeat picker. */
fun repeatMenuLabel(unit: RepeatUnit): String = when (unit) {
    RepeatUnit.NONE -> "Does not repeat"
    RepeatUnit.DAY -> "Daily"
    RepeatUnit.WEEK -> "Weekly"
    RepeatUnit.MONTH -> "Monthly"
    RepeatUnit.YEAR -> "Yearly"
}
