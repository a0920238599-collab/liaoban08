package com.realtek.chat.ai

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.realtek.chat.settings.AppSettings
import com.realtek.chat.storage.AppDb
import com.realtek.chat.storage.Contact
import com.realtek.chat.storage.GroupMessage
import com.realtek.chat.storage.ProfileStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GroupSpeakDecision(
    val contact: Contact,
    val speak: Boolean,
    val priority: Int,
    val mode: String,
    val direction: String,
    val targetLabel: String,
    val delayClass: String
)

class GroupChatEngine(context: Context) {
    private val appContext =
        context.applicationContext

    private val db =
        AppDb.get(appContext)

    private val settings =
        AppSettings(appContext)

    private val gateway =
        RunApiGateway(appContext)

    private val personaEvolution =
        PersonaEvolutionEngine(
            appContext
        )

    private val profile =
        ProfileStore(appContext)

    private val episodeMutex =
        Mutex()

    suspend fun sendUserText(
        groupId: Int,
        text: String,
        onChanged: () -> Unit = {},
        onTyping: (String?) -> Unit = {}
    ) {
        val clean = text.trim()
        if (clean.isBlank()) return

        db.addGroupMessage(
            groupId = groupId,
            senderKind = "user",
            senderContactId = null,
            senderName =
                profile.nickname
                    .ifBlank {
                        "我"
                    },
            type = "text",
            text = clean
        )

        onChanged()

        runCatching {
            personaEvolution
                .maybeLearnBeforeGroupReply(
                    groupId = groupId,
                    latestUserText = clean
                )
        }

        // 用户可以在群里继续发消息，不会取消已经在进行的群聊思路。
        // 新消息会成为下一次行为判断的最新事件。
        episodeMutex.withLock {
            runGroupEpisode(
                groupId = groupId,
                onChanged = onChanged,
                onTyping = onTyping
            )
        }
    }

    suspend fun processPersonaEvolution(
        groupId: Int
    ) = coroutineScope {
        db.getGroupMembers(groupId)
            .map {
                contact ->
                async {
                    runCatching {
                        personaEvolution
                            .learnFromGroupChat(
                                groupId,
                                contact
                            )
                    }
                }
            }
            .awaitAll()
    }

    private suspend fun runGroupEpisode(
        groupId: Int,
        onChanged: () -> Unit,
        onTyping: (String?) -> Unit
    ) {
        var safetyIterations = 0
        val safetyLimit = 16

        while (
            safetyIterations <
                safetyLimit
        ) {
            val latest =
                db.getLastGroupMessage(
                    groupId
                )
                    ?: return

            val members =
                db.getGroupMembers(
                    groupId
                )

            if (
                members.size < 2
            ) {
                return
            }

            val recent =
                db.getRecentGroupMessages(
                    groupId,
                    20
                )

            val aiTurnsSinceUser =
                recent.asReversed()
                    .takeWhile {
                        it.senderKind ==
                            "contact"
                    }
                    .size

            val decisions =
                coroutineScope {
                    members.map {
                        contact ->
                        async {
                            decideForMember(
                                groupId =
                                    groupId,
                                contact =
                                    contact,
                                latest =
                                    latest,
                                aiTurnsSinceUser =
                                    aiTurnsSinceUser
                            )
                        }
                    }.awaitAll()
                }

            val eligible =
                decisions.filter {
                    it.speak
                }

            if (
                eligible.isEmpty()
            ) {
                onTyping(null)
                return
            }

            val winner =
                eligible.maxWithOrNull(
                    compareBy<GroupSpeakDecision> {
                        it.priority
                    }.thenBy {
                        if (
                            latest.senderContactId ==
                                it.contact.id
                        ) {
                            0
                        } else {
                            1
                        }
                    }
                ) ?: return

            val requiredPriority =
                when {
                    aiTurnsSinceUser < 3 ->
                        50
                    aiTurnsSinceUser < 6 ->
                        68
                    aiTurnsSinceUser < 9 ->
                        82
                    else ->
                        93
                }

            if (
                winner.priority <
                    requiredPriority
            ) {
                onTyping(null)
                return
            }

            delay(
                delayFor(
                    winner,
                    aiTurnsSinceUser
                )
            )

            val beforeGenerate =
                db.getLastGroupMessage(
                    groupId
                )
                    ?: return

            if (
                beforeGenerate.id !=
                    latest.id
            ) {
                // 期间用户或其他成员又发了消息。
                // 不停止群聊，也不“等用户说完”，直接用新事件重新判断下一步。
                safetyIterations += 1
                continue
            }

            onTyping(
                winner.contact.name
            )

            val reply =
                generateForMember(
                    groupId =
                        groupId,
                    decision =
                        winner
                )

            onTyping(null)

            if (
                reply.isNullOrBlank()
            ) {
                return
            }

            // 生成期间即使出现新的用户消息，这条已经形成的消息也可以照常发出，
            // 形成真实文字聊天里的交叉发送。
            db.addGroupMessage(
                groupId =
                    groupId,
                senderKind =
                    "contact",
                senderContactId =
                    winner.contact.id,
                senderName =
                    winner.contact.name,
                type = "text",
                text =
                    reply.take(
                        1200
                    )
            )

            onChanged()
            safetyIterations += 1
        }

        onTyping(null)
    }

    private suspend fun decideForMember(
        groupId: Int,
        contact: Contact,
        latest: GroupMessage,
        aiTurnsSinceUser: Int
    ): GroupSpeakDecision {
        val stop =
            GroupSpeakDecision(
                contact = contact,
                speak = false,
                priority = 0,
                mode = "wait",
                direction = "",
                targetLabel = "",
                delayClass = "normal"
            )

        if (
            !settings.providerConfigured(
                contact.provider
            )
        ) {
            return stop
        }

        val members =
            db.getGroupMembers(
                groupId
            )

        val recent =
            db.getRecentGroupMessages(
                groupId,
                18
            )

        val roster =
            buildRoster(
                members
            )

        val transcript =
            buildTranscript(
                recent
            )

        val latestLabel =
            speakerLabel(latest)

        val adaptive =
            personaEvolution
                .effectiveRules(
                    contact.id
                )

        val raw =
            withTimeoutOrNull(
                5_000L
            ) {
                runCatching {
                    gateway.chat(
                        provider =
                            contact.provider,
                        model =
                            contact.model,
                        systemPrompt = """
                            你现在是群聊成员“${contact.name}”自己的社交行为决策层。
                            你只决定“${contact.name}现在要不要发言”，不写最终聊天内容。

                            【身份隔离是最高优先级】
                            群里每个 speaker ID 都是完全独立的聊天成员。
                            USER 说的话只属于用户本人。
                            CONTACT:${contact.id} 才是你自己。
                            其他 CONTACT 的话属于其他独立成员。
                            绝不能把不同 speaker 的话合并成一个人，
                            也绝不能把其他联系人的话记成用户对你说的话。

                            【群成员】
                            $roster

                            【你自己的初始人格】
                            ${contact.corePersona}

                            【你自己的动态相处规则】
                            $adaptive

                            判断原则：
                            1. 可以回应用户，也可以回应另一个联系人。
                            2. 不要求每条用户消息所有 AI 都回答。
                            3. 最新说话者如果是另一个联系人，可以自然接他的话、反驳、补充、调侃或不说。
                            4. 如果最新说话者就是你自己，不要机械连发；只有真的还有独立内容才继续。
                            5. 群里已经连续很多 AI 消息而用户没说话时，越来越应该收住，除非新消息很值得。
                            6. 不要为了刷存在感而发言。
                            7. 被明确点名、被直接提问、与你强相关时优先级可以高。
                            8. 不要因为判断“别人可能还没说完/用户可能还要输入”就自动沉默。
                               这是文字群聊，消息可以交叉；只根据你有没有自然内容、群聊压力和相关性决定。
                            9. 你只能代表“${contact.name}”自己，不能替其他成员说话。
                        """.trimIndent(),
                        userPrompt = """
                            最近群聊：
                            $transcript

                            最新说话者：
                            $latestLabel

                            自从用户上一条消息以后，
                            AI已经连续发了：
                            $aiTurnsSinceUser 条

                            只输出 JSON：
                            {
                              "speak": true,
                              "priority": 78,
                              "mode": "reply",
                              "direction": "回应B刚才的观点",
                              "target": "[CONTACT:12|B]",
                              "delay_class": "normal"
                            }

                            mode 可以是：
                            reaction
                            reply
                            clarify
                            self_share
                            topic_extend
                            question
                            wait

                            delay_class 可以是：
                            quick
                            normal
                            thoughtful
                        """.trimIndent(),
                        maxTokens = 150
                    )
                }.getOrNull()
            } ?: return stop

        val obj =
            parseJson(raw)
                ?: return stop

        val speak =
            obj["speak"]
                ?.takeIf {
                    it.isJsonPrimitive
                }
                ?.asBoolean
                ?: false

        if (!speak) {
            return stop
        }

        var priority =
            obj["priority"]
                ?.takeIf {
                    it.isJsonPrimitive
                }
                ?.asInt
                ?.coerceIn(
                    0,
                    100
                )
                ?: 0

        // 同一个联系人刚说完又马上抢下一条，
        // 必须有更强理由。
        if (
            latest.senderKind ==
                "contact" &&
            latest.senderContactId ==
                contact.id
        ) {
            priority -= 16
        }

        return GroupSpeakDecision(
            contact = contact,
            speak = true,
            priority =
                priority.coerceIn(
                    0,
                    100
                ),
            mode =
                obj["mode"]
                    ?.takeIf {
                        it.isJsonPrimitive
                    }
                    ?.asString
                    ?.trim()
                    .orEmpty()
                    .ifBlank {
                        "reply"
                    },
            direction =
                obj["direction"]
                    ?.takeIf {
                        it.isJsonPrimitive
                    }
                    ?.asString
                    ?.trim()
                    .orEmpty()
                    .take(220),
            targetLabel =
                obj["target"]
                    ?.takeIf {
                        it.isJsonPrimitive
                    }
                    ?.asString
                    ?.trim()
                    .orEmpty()
                    .take(120),
            delayClass =
                obj["delay_class"]
                    ?.takeIf {
                        it.isJsonPrimitive
                    }
                    ?.asString
                    ?.trim()
                    ?.takeIf {
                        it in setOf(
                            "quick",
                            "normal",
                            "thoughtful"
                        )
                    }
                    ?: "normal"
        )
    }

    private suspend fun generateForMember(
        groupId: Int,
        decision: GroupSpeakDecision
    ): String? {
        val contact =
            decision.contact

        val members =
            db.getGroupMembers(
                groupId
            )

        val recent =
            db.getRecentGroupMessages(
                groupId,
                20
            )

        val roster =
            buildRoster(
                members
            )

        val transcript =
            buildTranscript(
                recent
            )

        val adaptive =
            personaEvolution
                .effectiveRules(
                    contact.id
                )

        return runCatching {
            gateway.chat(
                provider =
                    contact.provider,
                model =
                    contact.model,
                systemPrompt = """
                    你是群聊成员“${contact.name}”。

                    【群成员身份表】
                    $roster

                    【严格身份规则】
                    - [USER:user|...] 是用户本人。
                    - [CONTACT:${contact.id}|${contact.name}] 是你自己。
                    - 其他 [CONTACT:id|name] 是其他独立联系人。
                    - A说的话只能算A说的，B说的话只能算B说的。
                    - 不要把多个成员的话合并成“用户说的”。
                    - 不要把群聊消息写成私聊上下文。
                    - 只以“${contact.name}”自己的身份发言。

                    【初始人格】
                    ${contact.corePersona}

                    【初始说话方式】
                    ${contact.styleRules}

                    【相处过程中学到的动态规则，优先于初始表达习惯】
                    $adaptive

                    初始人物卡中“每次必须、固定几条、固定结尾表情、每次追问”等动作不能机械执行；
                    用户后来的明确反馈和动态相处规则优先。

                    ${SocialConstitution.runtimeRules}

                    【本次行为决策】
                    mode=${decision.mode}
                    target=${decision.targetLabel}
                    direction=${decision.direction}

                    这是群聊，不要求所有话都围着用户。
                    你可以自然回应另一个联系人，
                    也可以对用户说话。
                    只发“${contact.name}”这一条消息本身。
                    不要在正文前写名字或 speaker ID。
                """.trimIndent(),
                userPrompt = """
                    最近群聊：
                    $transcript

                    现在按上面的行为决策自然说这一条。
                """.trimIndent(),
                maxTokens = 260
            ).trim()
        }.getOrNull()
            ?.takeIf {
                it.isNotBlank()
            }
    }

    private fun buildRoster(
        members: List<Contact>
    ): String {
        val contacts =
            members.joinToString("\n") {
                "[CONTACT:${it.id}|${it.name}]"
            }

        return """
            [USER:user|${profile.nickname.ifBlank { "我" }}]
            $contacts
        """.trimIndent()
    }

    private fun buildTranscript(
        messages: List<GroupMessage>
    ): String =
        messages.joinToString("\n") {
            "${speakerLabel(it)}：${it.text}"
        }
            .ifBlank {
                "暂无"
            }

    private fun speakerLabel(
        message: GroupMessage
    ): String =
        if (
            message.senderKind ==
                "user"
        ) {
            "[USER:user|${message.senderName}]"
        } else {
            val stableId =
                message.senderKey
                    .removePrefix(
                        "contact:"
                    )

            "[CONTACT:$stableId|${message.senderName}]"
        }

    private fun delayFor(
        decision: GroupSpeakDecision,
        aiTurnsSinceUser: Int
    ): Long {
        val base =
            when (
                decision.delayClass
            ) {
                "quick" -> 650L
                "thoughtful" -> 2_300L
                else -> 1_250L
            }

        return (
            base +
                aiTurnsSinceUser *
                    240L
            ).coerceIn(
            450L,
            4_800L
        )
    }

    private fun parseJson(
        raw: String
    ): JsonObject? {
        val clean =
            raw.trim()
                .removePrefix(
                    "```json"
                )
                .removePrefix(
                    "```JSON"
                )
                .removePrefix(
                    "```"
                )
                .removeSuffix(
                    "```"
                )
                .trim()

        runCatching {
            JsonParser.parseString(
                clean
            ).asJsonObject
        }.getOrNull()?.let {
            return it
        }

        val start =
            clean.indexOf('{')

        val end =
            clean.lastIndexOf('}')

        if (
            start >= 0 &&
            end > start
        ) {
            return runCatching {
                JsonParser.parseString(
                    clean.substring(
                        start,
                        end + 1
                    )
                ).asJsonObject
            }.getOrNull()
        }

        return null
    }
}
