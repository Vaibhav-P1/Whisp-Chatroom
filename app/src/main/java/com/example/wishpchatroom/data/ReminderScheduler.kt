package com.example.wishpchatroom.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.wishpchatroom.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** How long before a mission ends the reminder fires. */
const val REMINDER_LEAD_MS = 10L * 60 * 1000

/** Milliseconds until the reminder should fire, or null if less than the lead time is left. */
fun reminderDelayMs(expiresAtMillis: Long, nowMillis: Long): Long? =
    (expiresAtMillis - REMINDER_LEAD_MS - nowMillis).takeIf { it > 0 }

/**
 * Local reminders that a mission is about to end. Everything runs on the device (no backend): the
 * notification text never contains message content, only the mission title.
 */
interface ReminderScheduler {
    fun isScheduled(roomCode: String): Boolean

    /** Fires a notification after [delayMillis]. Replaces an earlier reminder for the same room. */
    fun schedule(roomCode: String, title: String, delayMillis: Long)

    fun cancel(roomCode: String)

    /** Used when the account is deleted. */
    fun cancelAll()
}

private const val PREFS = "mission_reminders"
private const val CHANNEL_ID = "mission_alerts"

@Singleton
class WorkManagerReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) : ReminderScheduler {

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun isScheduled(roomCode: String): Boolean = roomCode in prefs.getStringSet(KEY_ROOMS, emptySet()).orEmpty()

    override fun schedule(roomCode: String, title: String, delayMillis: Long) {
        val request = OneTimeWorkRequestBuilder<MissionReminderWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(MissionReminderWorker.KEY_ROOM to roomCode, MissionReminderWorker.KEY_TITLE to title))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(roomCode), ExistingWorkPolicy.REPLACE, request)
        updateRooms { it + roomCode }
    }

    override fun cancel(roomCode: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(roomCode))
        updateRooms { it - roomCode }
    }

    override fun cancelAll() {
        prefs.getStringSet(KEY_ROOMS, emptySet()).orEmpty().forEach {
            WorkManager.getInstance(context).cancelUniqueWork(workName(it))
        }
        prefs.edit().remove(KEY_ROOMS).apply()
    }

    private fun updateRooms(change: (Set<String>) -> Set<String>) {
        val current = prefs.getStringSet(KEY_ROOMS, emptySet()).orEmpty()
        prefs.edit().putStringSet(KEY_ROOMS, change(current)).apply()
    }

    private fun workName(roomCode: String) = "mission-reminder-$roomCode"

    companion object {
        const val KEY_ROOMS = "rooms"

        /** Called by the worker once it fired, so the screen shows the reminder as used. */
        fun forget(context: Context, roomCode: String) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_ROOMS, prefs.getStringSet(KEY_ROOMS, emptySet()).orEmpty() - roomCode).apply()
        }
    }
}

class MissionReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val roomCode = inputData.getString(KEY_ROOM) ?: return Result.success()
        val title = inputData.getString(KEY_TITLE).orEmpty()
        showReminder(applicationContext, roomCode, title)
        WorkManagerReminderScheduler.forget(applicationContext, roomCode)
        return Result.success()
    }

    companion object {
        const val KEY_ROOM = "room"
        const val KEY_TITLE = "title"
    }
}

private fun showReminder(context: Context, roomCode: String, title: String) {
    // Without the permission (Android 13+) the notification would be dropped anyway.
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return

    if (Build.VERSION.SDK_INT >= 26) {
        val channel = NotificationChannel(CHANNEL_ID, "Mission reminders", NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // Tapping opens the room: wishp://room/CODE is handled by MainActivity.
    val open = Intent(Intent.ACTION_VIEW, Uri.parse("wishp://room/$roomCode"), context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    val pending = PendingIntent.getActivity(
        context, roomCode.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle("Mission ending soon")
        .setContentText(if (title.isBlank()) "A mission ends in about 10 minutes." else "$title ends in about 10 minutes.")
        .setContentIntent(pending)
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(context).notify(roomCode.hashCode(), notification)
}
