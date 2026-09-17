package com.realtek.chat.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.realtek.chat.MainActivity
import com.realtek.chat.ai.ChatEngine
import com.realtek.chat.ai.SocialEngine
import com.realtek.chat.settings.AppSettings
import com.realtek.chat.storage.AppDb
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class ConversationFollowUpWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // v7.4：旧的 45~135 秒跟进机制已停用。
        // 保留这个 Worker 只为了让升级前已经排队的旧任务安全结束。
        return Result.success()
    }
}

object ConversationFollowUpScheduler {
    fun schedule(
        context: Context,
        contactId: Int,
        expectedLastMessageId: Long
    ) {
        // v7.4 不再创建延迟几十秒的跟进任务。
    }
}

class ProactiveWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (AppSettings(applicationContext).sleepMode) {
            return Result.success()
        }

        val hour = LocalDateTime.now().hour
        if (hour >= 23 || hour < 8) return Result.success()

        val db = AppDb.get(applicationContext)
        val engine = ChatEngine(applicationContext)
        val socialEngine = SocialEngine(applicationContext)
        val now = System.currentTimeMillis()

        for (contact in db.getContacts()) {
            if (!contact.proactiveEnabled) continue

            val last = db.getLastMessage(contact.id) ?: continue
            val sinceLastHours = (now - last.createdAt) / 3_600_000.0

            val state = socialEngine.snapshot(contact.id)

            // 主动聊天节奏不再根据“聊了多少条”推断关系阶段。
            // 只把当前联系人的主动倾向作为轻微节奏因素。
            val minQuietHours =
                (
                    4.8 -
                        state.socialDrive * 1.8
                    ).coerceIn(
                    2.2,
                    4.8
                )

            if (sinceLastHours < minQuietHours) continue

            val sinceProactive = contact.lastProactiveAt?.let {
                (now - it) / 3_600_000.0
            } ?: 999.0

            val minProactiveGap =
                (
                    8.5 -
                        state.socialDrive * 2.4
                    ).coerceIn(
                    4.2,
                    8.5
                )

            if (sinceProactive < minProactiveGap) continue

            val memories = db.getMemories(contact.id, 120)

            val dueSoon = memories
                .firstOrNull { m ->
                    m.dueAt?.let {
                        abs(it - now) <=
                            24 * 3_600_000L
                    } == true
                }

            val openLoop = memories
                .firstOrNull {
                    it.type == "open_loop"
                }

            // 非事件型主动联系只看是否已经有可承接的聊天上下文，
            // 不使用“刚认识/熟悉”这类阶段阈值。
            val relationshipReason =
                sinceLastHours >=
                    (
                        minQuietHours + 0.8
                        )

            if (
                dueSoon == null &&
                openLoop == null &&
                !relationshipReason
            ) continue

            val reason = when {
                dueSoon != null ->
                    "有一件双方已经知道的事情临近或刚发生：${dueSoon.content}。如果现在自然，可以承接它；不要重述背景。"

                openLoop != null ->
                    "你们还有一个没有真正结束的话题：${openLoop.content}。如果此刻自然，可以直接接它的后续；不自然就不要发。"

                else ->
                    "已经沉默了一段时间。只有当最近共享内容能产生自然的新联想、新观点或值得承接的方向时才主动；没有内容就不要发。不要根据聊天条数猜关系亲疏。"
            }

            val text = engine.maybeProactive(
                contact = contact,
                reason = reason,
                conversationContinuation = false
            )

            if (!text.isNullOrBlank()) {
                NotificationHelper.show(
                    applicationContext,
                    contact.id,
                    contact.name,
                    text
                )
            }
        }

        return Result.success()
    }
}

object ProactiveScheduler {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ProactiveWorker>(
            1,
            TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag("realtek_proactive")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "realtek_proactive",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

private object NotificationHelper {
    fun show(
        context: Context,
        id: Int,
        title: String,
        text: String
    ) {
        val manager = context
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channelId = "realtek_messages"
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                "消息",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        val pending = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        manager.notify(
            4000 + id,
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        )
    }
}
