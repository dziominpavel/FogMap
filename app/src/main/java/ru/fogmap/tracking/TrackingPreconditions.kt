package ru.fogmap.tracking

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/** Проверка предусловий трекинга (задача 3.4): без Play Services — заглушка. */
object TrackingPreconditions {
    fun playServicesAvailable(context: Context): Boolean =
        runCatching {
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        }.getOrDefault(false)
}
