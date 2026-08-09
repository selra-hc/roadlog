package app.roadlog.dashcam.services

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.roadlog.dashcam.MainActivity
import app.roadlog.dashcam.NotificationHelper
import app.roadlog.dashcam.R
import app.roadlog.dashcam.enums.RecorderState
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.Date

data class RecorderNotificationHelper(
    val context: Context,
) {
    private fun getNotificationChangeStateIntent(
        newState: RecorderState,
        requestCode: Int
    ): PendingIntent {
        return PendingIntent.getService(
            context,
            requestCode,
            Intent(context, context::class.java).apply {
                action = "changeState"
                putExtra("newState", newState.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createBaseNotification(): NotificationCompat.Builder {
        return NotificationCompat.Builder(
            context,
            NotificationHelper.RECORDER_CHANNEL_ID
        )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSmallIcon(R.drawable.launcher_monochrome_noopacity)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setChronometerCountDown(false)
    }

    fun buildStartingNotification(): Notification {
        return createBaseNotification()
            .setContentTitle(context.getString(R.string.ui_videoRecorder_state_recording_title))
            .setContentText(context.getString(R.string.ui_recorder_state_recording_description))
            .build()
    }

    fun buildRecordingNotification(recordingTime: Long): Notification {
        return createBaseNotification()
            .setUsesChronometer(true)
            .setOngoing(true)
            .setShowWhen(true)
            .setWhen(
                Date.from(
                    Calendar
                        .getInstance()
                        .also { it.add(Calendar.SECOND, -recordingTime.toInt()) }
                        .toInstant()
                ).time,
            )
            .addAction(
                R.drawable.ic_pause,
                context.getString(R.string.ui_recorder_action_pause_label),
                getNotificationChangeStateIntent(RecorderState.PAUSED, 2),
            )
            .setContentTitle(context.getString(R.string.ui_videoRecorder_state_recording_title))
            .setContentText(context.getString(R.string.ui_recorder_state_recording_description))
            .build()
    }

    fun buildPausedNotification(start: LocalDateTime): Notification {
        return createBaseNotification()
            .setContentTitle(context.getString(R.string.ui_recorder_state_paused_title))
            .setContentText(context.getString(R.string.ui_recorder_state_paused_description))
            .setOngoing(false)
            .setUsesChronometer(false)
            .setWhen(Date.from(start.atZone(ZoneId.systemDefault()).toInstant()).time)
            .addAction(
                R.drawable.ic_play,
                context.getString(R.string.ui_recorder_action_resume_label),
                getNotificationChangeStateIntent(RecorderState.RECORDING, 3),
            )
            .build()
    }
}
