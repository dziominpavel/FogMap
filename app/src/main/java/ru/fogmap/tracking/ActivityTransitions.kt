package ru.fogmap.tracking

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import ru.fogmap.diag.DevLog

/**
 * Переходы активности через PendingIntent (motion-state 2.1).
 *
 * Система доставляет переход «стоит → идёт/едет» сама, поднимая процесс на
 * broadcast — так же, как фиксы. Колбэк здесь не годится: он живёт в процессе
 * и умирает вместе с ним.
 */
object ActivityTransitions {

    const val ACTION = "ru.fogmap.tracking.ACTIVITY_TRANSITION"

    /** Переходы, которые нас реально интересуют: выход из покоя и три вида движения. */
    private val TRANSITIONS: List<ActivityTransition> by lazy {
        listOf(
            transition(DetectedActivity.STILL, enter = false),
            transition(DetectedActivity.WALKING, enter = true),
            transition(DetectedActivity.IN_VEHICLE, enter = true),
            transition(DetectedActivity.ON_BICYCLE, enter = true)
        )
    }

    private fun transition(activityType: Int, enter: Boolean): ActivityTransition =
        ActivityTransition.Builder()
            .setActivityType(activityType)
            .setActivityTransition(
                if (enter) ActivityTransition.ACTIVITY_TRANSITION_ENTER
                else ActivityTransition.ACTIVITY_TRANSITION_EXIT
            )
            .build()

    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 2,
        Intent(context, ActivityTransitionReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    /**
     * Подписка. false — переходы недоступны (нет GMS, нет разрешения):
     * запись продолжает работать на GPS, причина уходит в лог.
     */
    fun start(context: Context): Boolean = runCatching {
        ActivityRecognition.getClient(context)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(TRANSITIONS), pendingIntent(context))
        true
    }.getOrElse { e ->
        DevLog.w(
            "TRACK", "wake_source_unavailable",
            mapOf("source" to "ar", "err" to e.javaClass.simpleName)
        )
        false
    }

    fun stop(context: Context) {
        runCatching {
            ActivityRecognition.getClient(context)
                .removeActivityTransitionUpdates(pendingIntent(context))
        }
    }
}
