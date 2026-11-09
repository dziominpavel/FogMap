package ru.fogmap.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import ru.fogmap.diag.DevLog

/**
 * Приём переходов активности (motion-state 2.1): только метка и заявка на
 * подъём записи. Тяжёлой работы здесь нет — как и у приёма фиксов.
 */
class ActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ActivityTransitions.ACTION) return
        val events = runCatching { ActivityTransitionResult.extractResult(intent) }
            .getOrNull()?.transitionEvents ?: return
        val last = events.lastOrNull() ?: return
        val activity = map(last.activityType) ?: return
        // Выход из STILL и вход в движение важны одинаково: первый — «он пошёл».
        ActivitySignal.set(activity)
        DevLog.i(
            "TRACK", "activity_transition",
            mapOf(
                "activity" to activity.name,
                "enter" to (last.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER)
            )
        )
        InboxWork.enqueue(context)
    }

    private fun map(type: Int): MotionActivity? = when (type) {
        DetectedActivity.STILL -> MotionActivity.STILL
        DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_FOOT -> MotionActivity.WALK
        DetectedActivity.ON_BICYCLE -> MotionActivity.BIKE
        DetectedActivity.IN_VEHICLE -> MotionActivity.VEHICLE
        else -> null
    }
}
