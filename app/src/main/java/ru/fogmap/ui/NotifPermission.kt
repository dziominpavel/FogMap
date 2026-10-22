package ru.fogmap.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Логика запроса `POST_NOTIFICATIONS` (fix-track-gap-visibility 5.5).
 *
 * Спрашиваем один раз и только когда системный диалог вообще существует
 * (API 33+). Отказ не ломает запись: трекер работает без FGS-уведомления, а
 * экран проверки покажет пункт «уведомления» как проблему. Повторно не
 * спрашиваем — иначе отказ превращается в навязчивый диалог.
 */
object NotifPermission {

    fun needsAsk(sdkInt: Int, granted: Boolean): Boolean = sdkInt >= 33 && !granted

    fun shouldAsk(sdkInt: Int, granted: Boolean, askedBefore: Boolean): Boolean =
        needsAsk(sdkInt, granted) && !askedBefore

    fun granted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
}
