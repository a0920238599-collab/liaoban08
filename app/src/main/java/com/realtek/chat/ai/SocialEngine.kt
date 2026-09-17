package com.realtek.chat.ai

import android.content.Context
import com.realtek.chat.storage.AppDb
import com.realtek.chat.storage.Contact
import com.realtek.chat.storage.SocialState
import kotlin.math.max
import kotlin.math.min

class SocialEngine(context: Context) {
    private val db = AppDb.get(
        context.applicationContext
    )

    fun prepareState(
        contact: Contact,
        userText: String
    ): SocialState {
        val now =
            System.currentTimeMillis()

        val old =
            db.getSocialState(
                contact.id
            ) ?: defaultState(
                contact,
                now
            )

        val elapsedHours =
            (
                (
                    now -
                        old.updatedAt
                    ).coerceAtLeast(
                    0L
                )
                ).toDouble() /
                3_600_000.0

        val mood =
            inferConversationMood(
                userText,
                old.mood
            )

        val recoveredEnergy =
            min(
                0.90,
                old.conversationEnergy +
                    min(
                        0.20,
                        elapsedHours *
                            0.03
                    )
            )

        val conversationEnergy =
            when (mood) {
                "playful" ->
                    min(
                        0.95,
                        recoveredEnergy +
                            0.08
                    )

                "attentive" ->
                    max(
                        0.42,
                        recoveredEnergy -
                            0.03
                    )

                "quiet" ->
                    max(
                        0.30,
                        recoveredEnergy -
                            0.05
                    )

                else ->
                    recoveredEnergy
            }

        val personaDrive =
            personaInitiativeSeed(
                contact
            )

        val socialDrive =
            (
                old.socialDrive *
                    0.72 +
                    personaDrive *
                    0.28
                ).coerceIn(
                0.15,
                0.95
            )

        // 关系亲疏不由聊天条数自动推导。
        // familiarity / trust / humorComfort 仅作为旧数据兼容的连续信号。
        val state =
            old.copy(
                mood = mood,
                conversationEnergy =
                    conversationEnergy,
                socialDrive =
                    socialDrive,
                updatedAt =
                    now
            )

        db.upsertSocialState(
            state
        )

        return state
    }

    fun applyPlan(
        state: SocialState,
        plan: ConversationPlan
    ): SocialState {
        val energyCost =
            when (plan.mode) {
                "talk_burst" -> 0.035
                "answer" -> 0.020
                "react" -> 0.012
                else -> 0.008
            }

        val updated =
            state.copy(
                currentTopic =
                    plan.topicLabel
                        .ifBlank {
                            state.currentTopic
                        },
                // phase 仅保留数据库兼容。
                phase = "continuous",
                conversationEnergy =
                    (
                        state.conversationEnergy -
                            energyCost
                        ).coerceIn(
                        0.20,
                        0.95
                    ),
                updatedAt =
                    System.currentTimeMillis()
            )

        db.upsertSocialState(
            updated
        )

        return updated
    }

    fun afterAssistantMessage(
        contactId: Int,
        text: String
    ) {
        val topic =
            inferSelfTopic(text)

        if (
            topic != "general" &&
            topic.isNotBlank()
        ) {
            db.addSelfTopic(
                contactId,
                topic
            )
        }
    }

    fun snapshot(
        contactId: Int
    ): SocialState =
        db.getSocialState(contactId)
            ?: defaultState(
                contactId,
                System.currentTimeMillis()
            )

    fun recentTopicSummary(
        contactId: Int
    ): String {
        val topics =
            db.getRecentSelfTopics(
                contactId,
                14
            )

        if (topics.isEmpty()) {
            return "暂无近期自我话题冷却。"
        }

        val now =
            System.currentTimeMillis()

        return topics
            .groupBy {
                it.topic
            }
            .map { (topic, items) ->
                val latest =
                    items.maxOf {
                        it.createdAt
                    }

                val hours =
                    (
                        now - latest
                        ).coerceAtLeast(0L)
                        .toDouble() /
                        3_600_000.0

                "$topic（${"%.1f".format(hours)}小时前）"
            }
            .take(8)
            .joinToString("；")
    }

    fun topicOnCooldown(
        contactId: Int,
        candidate: String
    ): Boolean {
        val topic =
            inferSelfTopic(candidate)

        if (
            topic == "general" ||
            topic.isBlank()
        ) {
            return false
        }

        val cooldownHours =
            when (topic) {
                "self_fatigue" -> 10.0
                "self_sleep" -> 8.0
                "weather" -> 6.0
                "boredom" -> 6.0
                "food" -> 4.0
                else -> 3.0
            }

        val now =
            System.currentTimeMillis()

        return db.getRecentSelfTopics(
            contactId,
            30
        ).any {
            it.topic == topic &&
                (
                    now - it.createdAt
                    ).coerceAtLeast(0L)
                    .toDouble() /
                    3_600_000.0 <
                    cooldownHours
        }
    }

    fun inferSelfTopic(
        text: String
    ): String {
        val clean =
            text.trim()

        val fatigueWords = listOf(
            "好累",
            "累死",
            "累了",
            "疲惫",
            "没精神",
            "没力气"
        )

        val sleepWords = listOf(
            "困死",
            "好困",
            "想睡",
            "睡觉",
            "没睡醒"
        )

        val likelyAboutUser =
            clean.contains("你") &&
                !clean.contains("我")

        return when {
            !likelyAboutUser &&
                fatigueWords.any {
                    clean.contains(it)
                } -> "self_fatigue"

            !likelyAboutUser &&
                sleepWords.any {
                    clean.contains(it)
                } -> "self_sleep"

            listOf(
                "下雨",
                "天气",
                "好冷",
                "好热",
                "太阳"
            ).any {
                clean.contains(it)
            } -> "weather"

            listOf(
                "无聊",
                "没事干",
                "闲着"
            ).any {
                clean.contains(it)
            } -> "boredom"

            listOf(
                "饿",
                "吃饭",
                "吃啥",
                "好吃"
            ).any {
                clean.contains(it)
            } -> "food"

            else -> "general"
        }
    }

    private fun inferConversationMood(
        text: String,
        oldMood: String
    ): String {
        val clean =
            text.trim()

        return when {
            listOf(
                "哈哈",
                "笑死",
                "好玩",
                "逗",
                "离谱"
            ).any {
                clean.contains(it)
            } -> "playful"

            listOf(
                "难受",
                "烦",
                "气死",
                "崩溃",
                "委屈",
                "压力"
            ).any {
                clean.contains(it)
            } -> "attentive"

            clean.length <= 3 ->
                "quiet"

            clean.contains("？") ||
                clean.contains("?") ->
                "curious"

            oldMood.isBlank() ->
                "calm"

            else ->
                oldMood
        }
    }

    private fun defaultState(
        contact: Contact,
        now: Long
    ): SocialState {
        val relationSeed =
            relationSeed(
                contact
            )

        return SocialState(
            contactId =
                contact.id,
            mood = "calm",
            conversationEnergy =
                0.68,
            socialDrive =
                personaInitiativeSeed(
                    contact
                ),
            currentTopic = "",
            phase = "continuous",
            familiarity =
                relationSeed,
            trust =
                (
                    relationSeed *
                        0.88
                    ).coerceIn(
                    0.20,
                    0.90
                ),
            humorComfort =
                (
                    0.35 +
                        relationSeed *
                            0.45
                    ).coerceIn(
                    0.25,
                    0.88
                ),
            updatedAt = now
        )
    }

    private fun defaultState(
        contactId: Int,
        now: Long
    ): SocialState =
        SocialState(
            contactId =
                contactId,
            mood = "calm",
            conversationEnergy =
                0.68,
            socialDrive =
                0.50,
            currentTopic = "",
            phase = "continuous",
            familiarity =
                0.50,
            trust =
                0.50,
            humorComfort =
                0.50,
            updatedAt = now
        )

    private fun relationSeed(
        contact: Contact
    ): Double {
        val source =
            (
                contact.corePersona +
                    " " +
                    contact.styleRules +
                    " " +
                    contact.subtitle
                ).lowercase()

        return when {
            listOf(
                "恋人",
                "情侣",
                "对象",
                "夫妻",
                "家人",
                "发小",
                "闺蜜",
                "兄弟",
                "老朋友",
                "多年好友",
                "从小认识"
            ).any {
                source.contains(it)
            } -> 0.84

            listOf(
                "朋友",
                "同学",
                "同事",
                "搭档",
                "室友"
            ).any {
                source.contains(it)
            } -> 0.62

            listOf(
                "刚认识",
                "陌生",
                "初次见面"
            ).any {
                source.contains(it)
            } -> 0.26

            else -> 0.50
        }
    }

    private fun personaInitiativeSeed(
        contact: Contact
    ): Double {
        val source =
            (
                contact.corePersona +
                    " " +
                    contact.styleRules
                ).lowercase()

        var value = 0.50

        if (
            listOf(
                "主动",
                "健谈",
                "爱聊",
                "活泼",
                "外向",
                "话多"
            ).any {
                source.contains(it)
            }
        ) {
            value += 0.18
        }

        if (
            listOf(
                "慢热",
                "克制",
                "话少",
                "安静",
                "内向"
            ).any {
                source.contains(it)
            }
        ) {
            value -= 0.14
        }

        return value.coerceIn(
            0.22,
            0.86
        )
    }

}
