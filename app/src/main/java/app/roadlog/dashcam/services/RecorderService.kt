package app.roadlog.dashcam.services

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import app.roadlog.dashcam.NotificationHelper
import app.roadlog.dashcam.enums.RecorderState
import app.roadlog.dashcam.ui.utils.PermissionHelper
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit


abstract class RecorderService : LifecycleService() {
    private val binder = RecorderBinder()

    private var isPaused: Boolean = false
    lateinit var recordingStart: LocalDateTime
        private set
    private lateinit var recordingTimeTimer: ScheduledExecutorService

    var state = RecorderState.IDLE
        private set

    var onStateChange: ((RecorderState) -> Unit)? = null
    var onError: () -> Unit = {}
    var onRecordingTimeChange: ((Long) -> Unit)? = null

    var recordingTime = 0L
        private set

    // Whether the app is currently in the foreground — set via `onAppBackgrounded()`/
    // `onAppForegrounded()` below, called from the concrete recorder service's own
    // `ProcessLifecycleOwner` observer (§9.6's energy-efficiency pass). Defaults true
    // since a recording session always starts from foreground UI interaction.
    private var isAppInForeground = true

    // Non-null while currently backgrounded (or backgrounded-then-paused) and RECORDING,
    // holding the timestamp from which elapsed seconds haven't been added to
    // `recordingTime` yet — flushed in one step by `flushBackgroundedElapsedTime()`
    // whenever that state ends (foregrounded, or paused), rather than relying on the
    // once-a-second ticks this mechanism deliberately skips while backgrounded.
    private var backgroundedSince: LocalDateTime? = null

    private fun flushBackgroundedElapsedTime() {
        val since = backgroundedSince ?: return
        backgroundedSince = null

        recordingTime += Duration.between(since, LocalDateTime.now()).seconds
        onRecordingTimeChange?.invoke(recordingTime)
    }

    protected open fun start() {
        if (isAppInForeground) {
            createRecordingTimeTimer()
        } else {
            backgroundedSince = LocalDateTime.now()
        }
    }

    protected open fun pause() {
        isPaused = true

        flushBackgroundedElapsedTime()
        if (::recordingTimeTimer.isInitialized) {
            recordingTimeTimer.shutdown()
        }
    }

    protected open fun resume() {
        if (isAppInForeground) {
            createRecordingTimeTimer()
        } else {
            backgroundedSince = LocalDateTime.now()
        }
    }

    protected open suspend fun stop() {
        flushBackgroundedElapsedTime()
        if (::recordingTimeTimer.isInitialized) {
            recordingTimeTimer.shutdown()
        }
    }

    // Stops the once-a-second UI-only elapsed-time tick while there's no foreground UI
    // to update — the foreground notification renders its own chronometer from a single
    // timestamp (`setUsesChronometer`/`setWhen`, see `buildNotification()` below), not
    // from these ticks, so nothing needs this timer running with no visible timer to
    // observe it (§9.6's energy-efficiency pass). No-op unless currently RECORDING
    // (PAUSED already has no running timer).
    fun onAppBackgrounded() {
        isAppInForeground = false

        if (state != RecorderState.RECORDING) {
            return
        }

        backgroundedSince = LocalDateTime.now()
        if (::recordingTimeTimer.isInitialized) {
            recordingTimeTimer.shutdown()
        }
    }

    // Recomputes the elapsed background time in one step (rather than relying on ticks
    // that were deliberately skipped) and restarts the timer if still RECORDING.
    fun onAppForegrounded() {
        isAppInForeground = true

        flushBackgroundedElapsedTime()
        if (state == RecorderState.RECORDING) {
            createRecordingTimeTimer()
        }
    }

    protected abstract fun startForegroundService()

    fun startRecording() {
        recordingStart = LocalDateTime.now()

        startForegroundService()
        changeState(RecorderState.RECORDING)

        try {
            start()
        } catch (error: RuntimeException) {
            error.printStackTrace()

            if (error !is AvoidErrorDialogError) {
                onError()
            }
        }
    }

    suspend fun stopRecording() {
        changeState(RecorderState.STOPPED)
        stop()
    }

    fun pauseRecording() {
        changeState(RecorderState.PAUSED)
    }

    fun resumeRecording() {
        changeState(RecorderState.RECORDING)
    }

    fun destroy() {
        NotificationManagerCompat.from(this)
            .cancel(NotificationHelper.RECORDER_CHANNEL_NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "changeState" -> {
                val newState = intent.getStringExtra("newState")?.let {
                    RecorderState.valueOf(it)
                } ?: RecorderState.STOPPED
                changeState(newState)
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    inner class RecorderBinder : Binder() {
        fun getService(): RecorderService = this@RecorderService
    }

    private fun createRecordingTimeTimer() {
        recordingTimeTimer = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleAtFixedRate(
                {
                    recordingTime += 1
                    onRecordingTimeChange?.invoke(recordingTime)
                },
                0,
                1,
                TimeUnit.SECONDS
            )
        }
    }

    // Used to change the state of the service
    // will internally call start() / pause() / resume() / stop()
    // Immediately after creating the service make sure to call `changeState(RecorderState.RECORDING)`
    @SuppressLint("MissingPermission")
    fun changeState(newState: RecorderState) {
        if (state == newState) {
            return
        }

        state = newState
        when (newState) {
            RecorderState.RECORDING -> {
                if (isPaused) {
                    resume()
                    isPaused = false
                }
                // `start` is handled by `startRecording`
            }

            RecorderState.PAUSED -> pause()

            else -> {}
        }

        // Update notification
        if (
            arrayOf(
                RecorderState.RECORDING,
                RecorderState.PAUSED
            ).contains(newState) &&
            PermissionHelper.hasGranted(this, android.Manifest.permission.POST_NOTIFICATIONS)
        ) {
            val notification = buildNotification()
            NotificationManagerCompat.from(this).notify(
                NotificationHelper.RECORDER_CHANNEL_NOTIFICATION_ID,
                notification
            )
        }

        onStateChange?.invoke(newState)
    }

    protected fun getNotificationHelper(): RecorderNotificationHelper {
        return RecorderNotificationHelper(this)
    }

    private fun buildNotification(): Notification {
        val notificationHelper = getNotificationHelper()

        return when (state) {
            RecorderState.RECORDING -> {
                notificationHelper.buildRecordingNotification(recordingTime)
            }

            RecorderState.PAUSED -> {
                notificationHelper.buildPausedNotification(recordingStart)
            }

            else -> {
                throw IllegalStateException("Notification can't be built in state $state")
            }
        }
    }


    // Throw this error if you show a dialog yourself.
    // This will prevent the service from showing their generic error dialog.
    class AvoidErrorDialogError : RuntimeException()
}