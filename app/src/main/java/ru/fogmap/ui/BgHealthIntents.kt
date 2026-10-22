package ru.fogmap.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Переходы к исправлению проблем фоновой записи (fix-track-gap-visibility 5.2).
 *
 * Каждый интент обязан пройти `PackageManager.resolveActivity`: на устройстве
 * без нужного экрана (не Huawei, урезанная прошивка) приложение НЕ падает и
 * открывает настройки приложения. Huawei-специфика — только кандидат в списке,
 * а не единственный путь.
 */
object BgHealthIntents {

    /** Настройки приложения: запасной переход для любой проблемы. */
    fun appDetails(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )

    /** Экран уведомлений именно этого приложения. */
    fun notificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /**
     * Батарея: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` резолвится только
     * с `data=package:...` (проверено на P60 Pro), запасной — общий список
     * исключений.
     */
    fun batteryCandidates(context: Context): List<Intent> = listOf(
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    )

    /** Автозапуск Huawei: основной экран, запасной и общий action. */
    fun autostartCandidates(): List<Intent> = listOf(
        Intent().setComponent(
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
        ),
        Intent().setComponent(
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
            )
        ),
        Intent("huawei.intent.action.HSM_STARTUPAPP_MANAGER")
    )

    /**
     * Первый реально открываемый интент. Чистая часть относительно платформы:
     * проверка резолва инжектится, поэтому тестируется без Android.
     */
    fun pick(
        candidates: List<Intent>,
        canResolve: (Intent) -> Boolean,
        fallback: Intent
    ): Intent = pickAny(candidates, canResolve, fallback)

    /**
     * Общая (тестируемая без Android) форма выбора: порядок кандидатов
     * сохраняется, первый резолвящийся выигрывает, иначе — запасной переход.
     */
    fun <T : Any> pickAny(
        candidates: List<T>,
        canResolve: (T) -> Boolean,
        fallback: T
    ): T = candidates.firstOrNull(canResolve) ?: fallback

    fun resolve(context: Context, candidates: List<Intent>): Intent {
        val pm = context.packageManager
        return pick(
            candidates,
            canResolve = { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null },
            fallback = appDetails(context)
        )
    }

    /** Открыть первый доступный экран; ошибка запуска откатывается на настройки. */
    fun open(context: Context, candidates: List<Intent>): Boolean {
        val target = resolve(context, candidates)
        val started = runCatching { context.startActivity(target) }.isSuccess
        if (started) return true
        return runCatching { context.startActivity(appDetails(context)) }.isSuccess
    }

    /** Читает состояние, ничего не меняя: уведомления включены на уровне app. */
    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Разрешение POST_NOTIFICATIONS нужно спрашивать только на API 33+. */
    fun needsNotificationPermission(context: Context): Boolean =
        NotifPermission.needsAsk(Build.VERSION.SDK_INT, NotifPermission.granted(context))
}
