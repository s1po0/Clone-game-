package app.mangascribe.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Dates {
    fun shortNow(): String =
        SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())
}
