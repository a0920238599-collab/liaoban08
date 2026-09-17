package com.realtek.chat.ai

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.realtek.chat.settings.AppSettings
import com.realtek.chat.storage.AppDb
import com.realtek.chat.storage.Contact
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min

data class SocialActionDecision(
    val continueSpeaking: Boolean,
    val mode: String,
    val direction: String,
    val novelty: String,
    val interruptionRisk: String,
    val standaloneValue: String,
    val question: Boolean
)

data class ConversationPressure(
    val score: Double,
    val summary: String
)

class SocialBehaviorController(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDb.get(appContext)
    private val settings = AppSettings(appContext)
    private val gateway = RunApiGateway(appContext)
    private val socialEngine = SocialEngine(appContext)
    private val personaEvolution =
        PersonaEvolutionEngine(
            appContext
        )

    fun shouldConsiderContinuation(
        userText: String
    ): Boolean {
        val clean = userText.trim()

        if (isExplicitContinueRequest(clean)) {
            return true
        }

        if (isClosingSignal(clean)) {
            return false
        }

        // 文字聊天允许消息交叉。
        // 用户可能继续发送，不构成自动停止理由。
        return true
    }

    suspend fun decideNextExtraMessage(
        contact: Contact,
        originalUserText: String,
        extraIndex: Int,
        explicitContinue: Boolean
    ): SocialActionDecision {
        val empty = SocialActionDecision(
            continueSpeaking = false,
            mode = "stop",
            direction = "",
            novelty = "low",
            interruptionRisk = "high",
            standaloneValue = "low",
            question = false
        )

        if (
            !settings.providerConfigured(
                contact.provider
            )
        ) {
            return empty
        }

        val pressure = conversationPressure(
            contact.id
        )

        // 高对话压力时，普通续聊先收住。明确“继续说”不受这一硬阈值限制。
        if (
            !explicitContinue &&
            pressure.score >= 0.90
        ) {
            return empty
        }

        val recent = db.getRecentMessages(
            contact.id,
            12
        )

        val transcript = recent.joinToString("\n") {
            "${if (it.role == "user") "用户" else contact.name}：" +
                if (it.type == "image") "[图片]" else it.text
        }

        val state = socialEngine.snapshot(
            contact.id
        )

        val requiredBar = when {
            explicitContinue -> "normal"
            extraIndex == 0 -> "medium"
            extraIndex == 1 -> "high"
            else -> "very_high"
        }

        val adaptivePersona =
            personaEvolution
                .effectiveRules(
                    contact.id
                )

        val raw = withTimeoutOrNull(
            4_500L
        ) {
            runCatching {
                gateway.chat(
                    provider = contact.provider,
                    model = contact.model,
                    systemPrompt = """
                        你现在是“${contact.name}”这个联系人的内部社交行为决策层。
                        你仍然使用这个联系人自己的模型，但此处不负责写最终聊天消息。

                        你现在只判断：联系人刚发完一条以后，是否值得“再单独发一条”。

                        联系人初始核心性格：
                        ${contact.corePersona}

                        联系人初始说话方式：
                        ${contact.styleRules}

                        与当前用户相处后形成的动态规则：
                        $adaptivePersona

                        动态规则优先于初始表达习惯。
                        初始人物卡中的“固定几条、每次必须、固定加表情、总要追问”
                        不能作为机械行为规则。

                        最重要的规则：
                        1. 第一次回复只需要自然；任何额外消息都必须重新证明自己有存在必要。
                        2. 判断标准不是“还有没有东西能说”，而是“有没有值得现在单独再发一条的新东西”。
                        3. 不要因为推测用户还想继续输入就自动停止。文字聊天允许双方交叉发送；
                           只有明确结束、内容不值得、重复或整体对话压力过高时才收住。
                        4. 对话压力高时，即使还有新观点，也可以选择收住。
                        5. 第二条门槛高于第一条；之后每多发一条，下一条的存在门槛继续提高。
                        6. 不存在“第三条以后强制停止”的聊天规则；真正有独立价值时可以继续，但每条都必须重新证明必要性。
                        7. social_drive 只是倾向，不是决定因素。
                        8. 共享背景不要重述；额外消息必须有新反应、新信息、新联想或必要澄清。
                        9. 不要为了防冷场而硬问问题。
                        10. 行为要有因果性，不靠随机、固定条数或固定周期。
                    """.trimIndent(),
                    userPrompt = """
                        联系人：${contact.name}

                        最初触发当前表达的用户消息：
                        $originalUserText

                        注意：之后用户可能又发了新消息。
                        “最近聊天”才是当前最权威上下文；不要因为用户继续发消息就自动停止。

                        这是第 ${extraIndex + 1} 次“额外消息”决策。
                        用户是否明确要求继续说：
                        $explicitContinue

                        当前门槛：
                        $requiredBar

                        当前连续聊天状态：
                        mood=${state.mood}
                        topic=${state.currentTopic}
                        social_drive=${"%.2f".format(state.socialDrive)}

                        双方关系直接根据人物设定、动态人格和最近聊天理解，
                        不根据创建时间或消息数量自动改变。

                        对话压力：
                        score=${"%.2f".format(pressure.score)}
                        ${pressure.summary}

                        最近聊天：
                        $transcript

                        最近联系人自我话题：
                        ${socialEngine.recentTopicSummary(contact.id)}

                        只输出 JSON：
                        {
                          "continue": true,
                          "mode": "afterthought",
                          "direction": "补充刚才遗漏的一个新角度",
                          "novelty": "high",
                          "interruption_risk": "low",
                          "standalone_value": "high",
                          "question": false
                        }

                        mode 只能是：
                        reaction
                        afterthought
                        clarification
                        self_share
                        followup_question
                        topic_extension

                        novelty / interruption_risk / standalone_value 只能是：
                        low / medium / high

                        interruption_risk 只表示“这条消息会不会显得多余、重复、打扰整体节奏”，
                        不能因为“用户可能还要继续说/可能正在输入”就判 high。
                    """.trimIndent(),
                    maxTokens = 170
                )
            }.getOrNull()
        } ?: return empty

        val obj = parseJson(raw) ?: return empty

        val decision = SocialActionDecision(
            continueSpeaking = obj["continue"]
                ?.takeIf { it.isJsonPrimitive }
                ?.asBoolean
                ?: false,
            mode = obj["mode"]
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.trim()
                ?.takeIf {
                    it in setOf(
                        "reaction",
                        "afterthought",
                        "clarification",
                        "self_share",
                        "followup_question",
                        "topic_extension"
                    )
                }
                ?: "afterthought",
            direction = obj["direction"]
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.trim()
                .orEmpty()
                .take(220),
            novelty = level(
                obj["novelty"]
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
            ),
            interruptionRisk = level(
                obj["interruption_risk"]
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
            ),
            standaloneValue = level(
                obj["standalone_value"]
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
            ),
            question = obj["question"]
                ?.takeIf { it.isJsonPrimitive }
                ?.asBoolean
                ?: false
        )

        if (!decision.continueSpeaking) {
            return empty
        }

        return if (
            passesThreshold(
                decision = decision,
                extraIndex = extraIndex,
                explicitContinue = explicitContinue,
                pressure = pressure.score
            )
        ) {
            decision
        } else {
            empty
        }
    }

    fun delayFor(
        contact: Contact,
        decision: SocialActionDecision,
        previousText: String,
        extraIndex: Int
    ): Long {
        var base = when (decision.mode) {
            "reaction" -> 650L
            "afterthought" -> 1_350L
            "clarification" -> 1_700L
            "followup_question" -> 1_900L
            "topic_extension" -> 2_250L
            "self_share" -> 2_700L
            else -> 1_600L
        }

        // 内容更长，像“想了一下”后再说，停顿自然稍长。
        base += when {
            previousText.length > 180 -> 900L
            previousText.length > 80 -> 500L
            previousText.length > 30 -> 220L
            else -> 0L
        }

        // 第三条比第二条稍微更慢，避免机关枪式连发。
        base += extraIndex * 450L

        val style = (
            contact.corePersona +
                " " +
                contact.styleRules
            ).lowercase()

        if (
            listOf(
                "反应快",
                "活泼",
                "健谈"
            ).any { style.contains(it) }
        ) {
            base -= 180L
        }

        if (
            listOf(
                "慢热",
                "克制",
                "沉稳"
            ).any { style.contains(it) }
        ) {
            base += 280L
        }

        return base.coerceIn(
            450L,
            5_500L
        )
    }

    fun conversationPressure(
        contactId: Int
    ): ConversationPressure {
        val recent = db.getRecentMessages(
            contactId,
            14
        )

        if (recent.isEmpty()) {
            return ConversationPressure(
                0.0,
                "暂无足够历史。"
            )
        }

        val user = recent.filter {
            it.role == "user"
        }

        val assistant = recent.filter {
            it.role == "assistant"
        }

        val userChars = user.sumOf {
            it.text.length
        }

        val assistantChars = assistant.sumOf {
            it.text.length
        }

        val totalChars = max(
            1,
            userChars + assistantChars
        )

        val assistantCharShare =
            assistantChars.toDouble() /
                totalChars.toDouble()

        val consecutiveAssistant =
            recent.asReversed()
                .takeWhile {
                    it.role == "assistant"
                }
                .size

        val assistantQuestions =
            assistant.count {
                it.text.contains("?") ||
                    it.text.contains("？")
            }

        val lastAssistantLength =
            recent.asReversed()
                .firstOrNull {
                    it.role == "assistant"
                }
                ?.text
                ?.length
                ?: 0

        val latestUser =
            recent.asReversed()
                .firstOrNull {
                    it.role == "user"
                }

        val latestUserIndex =
            latestUser?.let {
                recent.indexOf(it)
            } ?: -1

        val assistantBeforeLatestUser =
            if (latestUserIndex > 0) {
                recent.subList(
                    0,
                    latestUserIndex
                )
                    .asReversed()
                    .firstOrNull {
                        it.role == "assistant"
                    }
            } else {
                null
            }

        val terseAfterHeavy =
            latestUser != null &&
                latestUser.text.length <= 5 &&
                (
                    assistantBeforeLatestUser
                        ?.text
                        ?.length
                        ?: 0
                    ) >= 90

        var score =
            assistantCharShare * 0.38 +
                min(
                    1.0,
                    consecutiveAssistant / 3.0
                ) * 0.25 +
                min(
                    1.0,
                    assistantQuestions / 4.0
                ) * 0.14 +
                min(
                    1.0,
                    lastAssistantLength / 350.0
                ) * 0.13

        if (terseAfterHeavy) {
            score += 0.10
        }

        score = score.coerceIn(
            0.0,
            1.0
        )

        val summary =
            "最近${recent.size}条：" +
                "用户${user.size}条/${userChars}字；" +
                "联系人${assistant.size}条/${assistantChars}字；" +
                "联系人连续发言${consecutiveAssistant}条；" +
                "问句${assistantQuestions}条；" +
                "最近联系人消息${lastAssistantLength}字；" +
                "用户是否在长回复后变短=${terseAfterHeavy}。"

        return ConversationPressure(
            score,
            summary
        )
    }

    private fun passesThreshold(
        decision: SocialActionDecision,
        extraIndex: Int,
        explicitContinue: Boolean,
        pressure: Double
    ): Boolean {
        if (explicitContinue) {
            return decision.interruptionRisk != "high"
        }

        if (pressure >= 0.88) {
            return false
        }

        return when {
            extraIndex == 0 ->
                decision.standaloneValue in setOf(
                    "medium",
                    "high"
                ) &&
                    decision.novelty in setOf(
                        "medium",
                        "high"
                    ) &&
                    decision.interruptionRisk != "high" &&
                    pressure < 0.80

            extraIndex == 1 ->
                decision.standaloneValue == "high" &&
                    decision.novelty == "high" &&
                    decision.interruptionRisk == "low" &&
                    pressure < 0.72

            extraIndex <= 3 ->
                decision.standaloneValue == "high" &&
                    decision.novelty == "high" &&
                    decision.interruptionRisk == "low" &&
                    pressure < 0.64

            else ->
                decision.standaloneValue == "high" &&
                    decision.novelty == "high" &&
                    decision.interruptionRisk == "low" &&
                    pressure < 0.55
        }
    }

    private fun isClosingSignal(
        text: String
    ): Boolean {
        val signals = listOf(
            "晚安",
            "拜拜",
            "先这样",
            "不聊了",
            "回头说",
            "等会聊",
            "一会再说",
            "我先忙了",
            "先忙了",
            "我开车去了",
            "开车去了",
            "我上课去了",
            "上课去了",
            "我要睡了",
            "我睡了",
            "先睡了",
            "我去忙了",
            "先不说了",
            "先挂了",
            "改天聊"
        )

        return signals.any {
            text.contains(it)
        }
    }

    private fun isExplicitContinueRequest(
        text: String
    ): Boolean {
        val signals = listOf(
            "继续说",
            "继续讲",
            "接着说",
            "接着讲",
            "然后呢",
            "后来呢",
            "多说点",
            "多聊点",
            "你说吧",
            "你继续",
            "再说点",
            "讲下去",
            "我听着",
            "你多讲一会",
            "陪我聊会儿",
            "你随便说"
        )

        return signals.any {
            text.contains(it)
        }
    }

    private fun level(
        raw: String?
    ): String =
        raw
            ?.lowercase()
            ?.trim()
            ?.takeIf {
                it in setOf(
                    "low",
                    "medium",
                    "high"
                )
            }
            ?: "low"

    private fun parseJson(
        raw: String
    ): JsonObject? {
        val clean = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        runCatching {
            JsonParser.parseString(
                clean
            ).asJsonObject
        }.getOrNull()?.let {
            return it
        }

        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')

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
