package com.realtek.chat.ai

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.realtek.chat.settings.AppSettings
import com.realtek.chat.storage.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException


class ChatEngine(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDb.get(appContext)
    private val gateway = RunApiGateway(appContext)
    private val contextEngine = ContextEngine(appContext)
    private val settings = AppSettings(appContext)
    private val media = SecureMediaStore(appContext)
    private val planner = ConversationPlanner(appContext)
    private val socialEngine = SocialEngine(appContext)
    private val repetitionGuard = RepetitionGuard(
        appContext,
        socialEngine
    )
    private val behaviorController =
        SocialBehaviorController(
            appContext
        )
    private val personaEvolution =
        PersonaEvolutionEngine(
            appContext
        )

    // 同一个联系人同一时刻只保留一条“自然续聊思路链”。
    // 用户可以继续发消息；这些新消息会进入现有续聊链的最新上下文，
    // 而不是因为每次发送都额外启动一条新的续聊链。
    private val continuationMutex =
        Mutex()

suspend fun sendText(
    contactId: Int,
    text: String,
    onChanged: () -> Unit = {},
    onStreaming: (String) -> Unit = {}
) {
    val clean =
        text.trim()

    if (
        clean.isBlank()
    ) {
        return
    }

    val contact =
        db.getContact(
            contactId
        ) ?: return

    db.addMessage(
        contactId,
        "user",
        "text",
        clean
    )

    onChanged()

    runCatching {
        personaEvolution
            .maybeLearnBeforeDirectReply(
                contact = contact,
                latestUserText = clean
            )
    }

    val fastMode =
        isFastCasualMessage(
            clean
        )

    val initialSocialState =
        socialEngine.prepareState(
            contact,
            clean
        )

    val plan =
        planner.plan(
            contact = contact,
            currentText = clean,
            fastMode = fastMode
        )

    val socialState =
        socialEngine.applyPlan(
            initialSocialState,
            plan
        )

    val selectedMemories =
        planner.resolveMemories(
            contact.id,
            plan
        )

    val firstReply =
        runInitialReply(
            contact = contact,
            currentText = clean,
            fastMode = fastMode,
            plan = plan,
            selectedMemories =
                selectedMemories,
            socialState =
                socialState,
            onChanged =
                onChanged,
            onStreaming =
                onStreaming
        )

    if (
        firstReply.isNullOrBlank()
    ) {
        return
    }

    // 当前聊天里的后续表达不是后台“主动消息”，
    // 所以不受 proactive/sleep 开关限制。
    //
    // 如果这个联系人已经有一条续聊思路链在运行，
    // 新用户消息不会把它取消，也不会再复制启动第二条续聊链。
    // 新消息会被正在运行的行为判断从“最近聊天”中看到。
    if (
        continuationMutex.tryLock()
    ) {
        try {
            maybeShortContinue(
                contact = contact,
                originalUserText =
                    clean,
                plan = plan,
                previousAssistantText =
                    firstReply,
                onChanged =
                    onChanged,
                onStreaming =
                    onStreaming
            )
        } finally {
            continuationMutex.unlock()
        }
    }
}

private suspend fun runInitialReply(
    contact: Contact,
    currentText: String,
    fastMode: Boolean,
    plan: ConversationPlan,
    selectedMemories: List<MemoryItem>,
    socialState: SocialState,
    onChanged: () -> Unit,
    onStreaming: (String) -> Unit
): String? {
    val bundle =
        contextEngine.build(
            contact = contact,
            currentText =
                currentText,
            fastMode =
                fastMode,
            plan = plan,
            selectedMemories =
                selectedMemories,
            socialState =
                socialState,
            recentSelfTopics =
                socialEngine
                    .recentTopicSummary(
                        contact.id
                    ),
            recentAssistantText =
                repetitionGuard
                    .recentAssistantText(
                        contact.id
                    )
        )

    val selectedModel =
        if (
            fastMode &&
            settings.fastChatModel
                .isNotBlank()
        ) {
            settings.fastChatModel
        } else {
            contact.model
        }

    val maxTokens =
        when {
            fastMode -> 180
            plan.mode ==
                "backchannel" -> 100
            else -> 520
        }

    val generated =
        generateGuardedStage(
            contact = contact,
            model = selectedModel,
            bundle = bundle,
            maxTokens = maxTokens,
            onStreaming =
                onStreaming
        )
            ?.trim()
            .orEmpty()

    if (
        generated.isBlank()
    ) {
        onStreaming("")
        return null
    }

    // 文字聊天允许消息交叉。
    // 即使用户在生成期间又发了新消息，这条已经形成的回复也不会被作废。
    db.addMessage(
        contact.id,
        "assistant",
        "text",
        generated.take(
            2200
        )
    )

    socialEngine
        .afterAssistantMessage(
            contact.id,
            generated
        )

    onStreaming("")
    onChanged()

    return generated
}


private suspend fun maybeShortContinue(
    contact: Contact,
    originalUserText: String,
    plan: ConversationPlan,
    previousAssistantText: String,
    onChanged: () -> Unit,
    onStreaming: (String) -> Unit
) {
    if (
        !behaviorController
            .shouldConsiderContinuation(
                userText =
                    originalUserText
            )
    ) {
        return
    }

    val explicitContinue =
        isExplicitContinueRequest(
            originalUserText
        )

    // 这里只是异常死循环保险，不是“最多说几条”的聊天规则。
    val safetyLimit =
        if (explicitContinue) {
            18
        } else {
            14
        }

    var extraIndex = 0
    var previousText =
        previousAssistantText

    while (
        extraIndex <
            safetyLimit
    ) {
        // 每一条发完以后都重新读取最新聊天。
        // 用户即使又发了消息，也只是新的聊天事件，不会自动取消当前联系人的表达。
        val decision =
            behaviorController
                .decideNextExtraMessage(
                    contact = contact,
                    originalUserText =
                        originalUserText,
                    extraIndex =
                        extraIndex,
                    explicitContinue =
                        explicitContinue
                )

        if (
            !decision.continueSpeaking
        ) {
            return
        }

        delay(
            behaviorController
                .delayFor(
                    contact = contact,
                    decision = decision,
                    previousText =
                        previousText,
                    extraIndex =
                        extraIndex
                )
        )

        val bundle =
            buildShortContinuationBundle(
                contact = contact,
                originalUserText =
                    originalUserText,
                decision =
                    decision,
                extraIndex =
                    extraIndex
            )

        val generated =
            generateShortContinuationSafely(
                contact = contact,
                bundle = bundle,
                onStreaming =
                    onStreaming
            )
                ?: return

        db.addMessage(
            contact.id,
            "assistant",
            "text",
            generated.take(
                1200
            )
        )

        socialEngine
            .afterAssistantMessage(
                contact.id,
                generated
            )

        previousText =
            generated

        onStreaming("")
        onChanged()

        extraIndex += 1
    }
}

private suspend fun generateShortContinuationSafely(
    contact: Contact,
    bundle: PromptBundle,
    onStreaming: (String) -> Unit
): String? {
    val first =
        runCatching {
            generateGuardedStage(
                contact = contact,
                model = contact.model,
                bundle = bundle,
                maxTokens = 220,
                onStreaming =
                    onStreaming
            )
        }.getOrNull()

    if (
        !first.isNullOrBlank()
    ) {
        return first
    }

    // 额外消息失败时静默重试一次。
    // 不因为用户又发了新消息而取消这次重试。
    onStreaming("")
    delay(550L)

    val retry =
        runCatching {
            generateGuardedStage(
                contact = contact,
                model = contact.model,
                bundle = bundle,
                maxTokens = 220,
                onStreaming =
                    onStreaming
            )
        }.getOrNull()

    if (
        retry.isNullOrBlank()
    ) {
        onStreaming("")
        return null
    }

    return retry
}

private fun isExplicitContinueRequest(
    text: String
): Boolean {
    val signals =
        listOf(
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

private fun buildShortContinuationBundle(
    contact: Contact,
    originalUserText: String,
    decision: SocialActionDecision,
    extraIndex: Int
): PromptBundle {
    val recent =
        db.getRecentMessages(
            contact.id,
            12
        )

    val transcript =
        recent.joinToString("\n") {
            "${if (it.role == "user") "用户" else contact.name}：" +
                if (
                    it.type == "image"
                ) {
                    "[图片]"
                } else {
                    it.text
                }
        }

    val state =
        socialEngine.snapshot(
            contact.id
        )

    val adaptivePersona =
        personaEvolution
            .effectiveRules(
                contact.id
            )

    val pressure =
        behaviorController
            .conversationPressure(
                contact.id
            )

    val system = """
        你正在作为联系人“${contact.name}”进行即时聊天。

        核心性格：
        ${contact.corePersona}

        初始说话方式：
        ${contact.styleRules}

        与用户相处后形成的动态规则：
        $adaptivePersona

        如果动态规则与初始表达习惯冲突，以动态规则为准。

        当前连续聊天状态：
        mood=${state.mood}
        current_topic=${state.currentTopic}
        social_drive=${"%.2f".format(state.socialDrive)}

        关系不是阶段变量。不要根据消息条数推断“刚认识/熟悉/很熟”。
        直接从初始人物设定、动态人格和真实聊天上下文理解关系。

        ${SocialConstitution.runtimeRules}

        【社交行为层已经决定】
        mode=${decision.mode}
        direction=${decision.direction}
        novelty=${decision.novelty}
        interruption_risk=${decision.interruptionRisk}
        standalone_value=${decision.standaloneValue}
        question=${decision.question}

        【当前对话压力】
        score=${"%.2f".format(pressure.score)}
        ${pressure.summary}

        【这条额外消息的要求】
        1. 这是第 ${extraIndex + 1} 条额外消息，不是重新回答最初的问题。
        2. 必须体现 mode 对应的行为：
           reaction = 第二反应
           afterthought = 后来又想到一点
           clarification = 补充刚才没说清楚的地方
           self_share = 分享一个相关观点/联想，不编造现实经历
           followup_question = 确实值得问的问题
           topic_extension = 对当前话题推进一个新角度
        3. 不要机械使用“对了 / 等等 / 我突然想到”作为固定开头。
        4. 必须推进新内容，不能复述刚才的话。
        5. 不要因为这是额外消息就故意写得碎。
        6. 如果 decision.question=false，就不要硬加问题。
        7. 不要写列表，不要解释内部决策。
    """.trimIndent()

    val user = """
        最初触发这段表达的用户消息：
        $originalUserText

        最近聊天（优先级更高，可能包含用户后来继续发的新消息）：
        $transcript

        用户后来继续发消息不代表你必须停下来等。
        如果你当前行为仍然有自然内容，就正常说；同时要理解最新消息。

        只输出这一条聊天消息本身。
    """.trimIndent()

    return PromptBundle(
        system = system,
        user = user
    )
}

    suspend fun sendImage(
        contactId: Int,
        mediaPath: String,
        onChanged: () -> Unit = {}
    ) {
        val contact = db.getContact(contactId) ?: return

        db.addMessage(
            contactId,
            "user",
            "image",
            "[图片]",
            mediaPath,
            "image/jpeg"
        )
        onChanged()

        val bytes = media.loadBytes(mediaPath)

        val raw = runCatching {
            gateway.chat(
                provider = contact.provider,
                model = contact.model,
                systemPrompt = """
                    你是联系人 ${contact.name}。
                    核心性格：${contact.corePersona}
                    说话方式：${contact.styleRules}
                    用户刚发了一张图片。像即时聊天联系人一样自然回应，不要自动写成图片分析报告。
                """.trimIndent(),
                userPrompt = "用户刚刚发送了一张图片，请结合图片自然回应。",
                imageBytes = bytes,
                imageMime = "image/jpeg",
                maxTokens = 260
            )
        }.getOrElse {
            gateway.chat(
                provider = contact.provider,
                model = contact.model,
                systemPrompt = """
                    你是联系人 ${contact.name}。
                    核心性格：${contact.corePersona}
                    说话方式：${contact.styleRules}
                """.trimIndent(),
                userPrompt = "用户刚刚发了一张图片，但当前模型未能读取图片。自然回应一下，不要假装看到了具体内容。",
                maxTokens = 160
            )
        }

        val imageReply =
            raw.trim().take(1200)

        db.addMessage(
            contact.id,
            "assistant",
            "text",
            imageReply
        )

        socialEngine.afterAssistantMessage(
            contact.id,
            imageReply
        )

        onChanged()
    }

    suspend fun generatePersona(
        name: String,
        description: String
    ): GeneratedPersona {
        require(
            settings.providerConfigured(
                settings.systemProvider
            )
        ) {
            "请先到“我 → 设置 → AI 服务”配置系统后台所使用的 API。"
        }

        val raw = gateway.chat(
            provider = settings.systemProvider,
            model = settings.defaultModelFor(
                settings.systemProvider
            ),
            systemPrompt = """
                你是人物设定编辑器。
                根据名字和用户的简单描述，生成一个适合长期即时聊天的“初始人格种子”。

                不要默认“新建联系人=刚认识”。
                如果用户描述里已经指定关系（老朋友、同事、家人、同学、熟人等），直接保留那个关系语境。
                如果没有指定，不要擅自给关系套阶段。

                初始人格只是起点，后续表达习惯会根据真实聊天和用户反馈继续变化。
                不写小说式背景，重点是核心性格、关系语境和自然聊天倾向。
            """.trimIndent(),
            userPrompt = """
                名字：${name.trim()}
                描述：${description.trim()}

                只输出 JSON：
                {
                  "subtitle":"不超过20字的简介",
                  "persona":"完整核心性格",
                  "style":"聊天表达习惯"
                }
            """.trimIndent(),
            maxTokens = 320
        )

        val obj = parseJson(raw)
            ?: error("人物设定生成失败，请再试一次。")

        return GeneratedPersona(
            subtitle = obj["subtitle"]
                ?.asString
                ?.trim()
                .orEmpty(),
            persona = obj["persona"]
                ?.asString
                ?.trim()
                .orEmpty(),
            style = obj["style"]
                ?.asString
                ?.trim()
                .orEmpty()
        )
    }

    suspend fun processPersonaEvolution(
        contactId: Int
    ) {
        val contact =
            db.getContact(contactId)
                ?: return

        personaEvolution
            .learnFromDirectChat(
                contact
            )
    }

    suspend fun processMemory(contactId: Int) {
        val contact = db.getContact(contactId) ?: return
        val count = db.userMessageCount(contactId)

        if (
            !settings.providerConfigured(
                settings.systemProvider
            ) ||
            count == 0 ||
            count % 5 != 0
        ) {
            return
        }

        val recent = db.getRecentMessages(contactId, 16)
        val oldSummary = db.getSummary(contactId)
            ?.summary
            .orEmpty()

        val transcript = recent.joinToString("\n") {
            "${if (it.role == "user") "用户" else contact.name}：" +
                if (it.type == "image") "[图片]" else it.text
        }

        val raw = runCatching {
            gateway.chat(
                provider = settings.systemProvider,
                model = settings.memoryModel.ifBlank {
                    settings.defaultModelFor(
                        settings.systemProvider
                    )
                },
                systemPrompt = """
                    你是聊天记忆整理器。
                    目标不是尽量多记，而是保留以后真正能帮助“理解上下文”的内容。
                    特别关注：
                    - 用户明确偏好、人物关系、习惯；
                    - 未来事件；
                    - 尚未结束/等待后续结果的话题（type=open_loop）；
                    - 共同经历；
                    - 用户习惯使用的替代表达。
                    - 只把“用户说过/用户经历/共同聊天中确实发生”的内容写进长期记忆。
                    - AI 联系人自己的临时自述（例如“我累了、我困了、我无聊”）不要提取为用户记忆，也不要写成长期事实。

                    tags 不只是关键词，还要包含可能的同义说法、代称和以后可能触发这段记忆的表达。
                    不记录密码、API Key 等秘密。
                """.trimIndent(),
                userPrompt = """
                    旧摘要：
                    ${oldSummary.ifBlank { "暂无" }}

                    最近聊天：
                    $transcript

                    只输出 JSON：
                    {
                      "summary":"不超过160字的新摘要",
                      "memories":[
                        {
                          "type":"preference/event/person/habit/shared_history/communication_style/open_loop",
                          "content":"一条独立可读的记忆",
                          "tags":"关键词、同义说法、代称、可能触发表达",
                          "importance":0.0,
                          "confidence":0.0,
                          "due_at":null
                        }
                      ]
                    }
                """.trimIndent(),
                maxTokens = 520
            )
        }.getOrNull() ?: return

        val obj = parseJson(raw) ?: return

        obj["summary"]
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                db.upsertSummary(
                    contactId,
                    it.take(600)
                )
            }

        val arr = obj.getAsJsonArray("memories")
            ?: return

        for (i in 0 until minOf(arr.size(), 5)) {
            val el = arr[i]
            if (!el.isJsonObject) continue

            val mo = el.asJsonObject
            val content = mo["content"]
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.trim()
                .orEmpty()

            if (
                content.length < 2 ||
                db.memoryExists(contactId, content)
            ) {
                continue
            }

            val importance = mo["importance"]
                ?.takeIf { it.isJsonPrimitive }
                ?.asDouble
                ?.coerceIn(0.0, 1.0)
                ?: 0.5

            val confidence = mo["confidence"]
                ?.takeIf { it.isJsonPrimitive }
                ?.asDouble
                ?.coerceIn(0.0, 1.0)
                ?: 0.7

            if (importance < 0.35 || confidence < 0.5) {
                continue
            }

            val dueAt = mo["due_at"]
                ?.takeIf {
                    it.isJsonPrimitive &&
                        !it.asString.equals("null", true)
                }
                ?.asString
                ?.let(::parseTime)

            db.addMemory(
                contactId = contactId,
                type = mo["type"]
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?: "event",
                content = content.take(360),
                tags = mo["tags"]
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    .orEmpty()
                    .take(200),
                importance = importance,
                confidence = confidence,
                dueAt = dueAt
            )
        }
    }

    suspend fun maybeProactive(
        contact: Contact,
        reason: String,
        conversationContinuation: Boolean = false
    ): String? {
        if (settings.sleepMode) return null
        if (
            !settings.providerConfigured(
                contact.provider
            )
        ) return null

        val recent = db.getRecentMessages(contact.id, 10)
        val summary = db.getSummary(contact.id)
            ?.summary
            .orEmpty()

        val memories = db.getMemories(contact.id, 100)
            .sortedWith(
                compareByDescending<MemoryItem> {
                    it.type == "open_loop"
                }.thenByDescending {
                    it.importance
                }
            )
            .take(6)

        val transcript = recent.joinToString("\n") {
            "${if (it.role == "user") "用户" else contact.name}：" +
                if (it.type == "image") "[图片]" else it.text
        }

        val socialState =
            socialEngine.snapshot(
                contact.id
            )

        val recentSelfTopics =
            socialEngine.recentTopicSummary(
                contact.id
            )

        val recentAssistantText =
            repetitionGuard.recentAssistantText(
                contact.id
            )

        val raw = runCatching {
            gateway.chat(
                provider = contact.provider,
                model = contact.model,
                systemPrompt = """
                    你是联系人 ${contact.name}。
                    核心性格：${contact.corePersona}
                    说话方式：${contact.styleRules}

                    你不是等用户提问才工作的问答助手。
                    可以自然地主动补一句或重新开启话题，
                    但不要机械刷存在感，不要像客服回访。

                    ${SocialConstitution.runtimeRules}

                    主动消息尤其注意：
                    - 主动必须有原因，不要只因为“时间到了”。
                    - 主动不等于问“在干嘛/吃了吗/今天怎么样”。
                    - 可以带来一个新观点、新联想、旧话题的后续思考。
                    - 没有真正的新内容时，宁可不发。

                    当前聊天行为状态：
                    mood=${socialState.mood}
                    social_drive=${"%.2f".format(socialState.socialDrive)}
                    familiarity=${"%.2f".format(socialState.familiarity)}
                    current_topic=${socialState.currentTopic.ifBlank { "暂无" }}

                    注意：这些是聊天行为状态，不是现实身体状态。
                    不要虚构自己刚下班、刚吃饭、身体很累、正在某个地点等现实经历。
                """.trimIndent(),
                userPrompt = """
                    触发原因：
                    $reason

                    类型：
                    ${if (conversationContinuation) "刚才对话的自然延续" else "隔一段时间后的主动联系"}

                    近期摘要：
                    ${summary.ifBlank { "暂无" }}

                    相关记忆：
                    ${memories.joinToString("\n") { "- ${it.content}" }.ifBlank { "暂无" }}

                    最近聊天：
                    ${transcript.ifBlank { "暂无" }}

                    你最近已经主动/自述过的主题：
                    $recentSelfTopics

                    你最近自己已经说过的话：
                    $recentAssistantText

                    重要：
                    - 不要再次用最近已经说过的“累、困、无聊、天气”等自我状态开场。
                    - 没有新的、自然的内容时，宁可 send=false。
                    - 如果要发，必须带来新话题、新进展或对未完成话题的自然承接。

                    只输出 JSON：
                    {
                      "send":true,
                      "messages":["第一条","可选第二条"]
                    }
                    或
                    {
                      "send":false,
                      "messages":[]
                    }

                    最多两条短消息。
                """.trimIndent(),
                maxTokens = 180
            )
        }.getOrNull() ?: return null

        val obj = parseJson(raw) ?: return null
        val send = obj["send"]
            ?.takeIf { it.isJsonPrimitive }
            ?.asBoolean
            ?: false

        val arr = obj.getAsJsonArray("messages")
        if (!send || arr == null || arr.size() == 0) {
            return null
        }

        val sent = mutableListOf<String>()

        for (i in 0 until minOf(arr.size(), 2)) {
            val text = arr[i].asString.trim()

            if (
                text.isBlank() ||
                repetitionGuard.isRepetitive(
                    contact.id,
                    text
                )
            ) {
                continue
            }

            if (i > 0) {
                delay(
                    naturalDelay(
                        text,
                        i
                    )
                )
            }

            db.addMessage(
                contact.id,
                "assistant",
                "text",
                text.take(600)
            )

            socialEngine.afterAssistantMessage(
                contact.id,
                text
            )

            sent += text
        }

        if (sent.isEmpty()) return null

        db.updateContact(
            contact.copy(
                lastProactiveAt = System.currentTimeMillis()
            )
        )

        return sent.joinToString("\n")
    }

    private suspend fun generateGuardedStage(
        contact: Contact,
        model: String,
        bundle: PromptBundle,
        maxTokens: Int,
        onStreaming: (String) -> Unit
    ): String? {
        var previewStarted = false
        var suspiciousPrefix = false

        val output = gateway.streamChat(
            provider = contact.provider,
            model = model,
            systemPrompt = bundle.system,
            userPrompt = bundle.user,
            maxTokens = maxTokens,
            onPartialText = partial@ { partial ->
                // Withhold the first few characters so very short repeats such as
                // “好累”“好困” can be rejected before the user sees them.
                if (!previewStarted) {
                    if (partial.length < 8) {
                        return@partial
                    }

                    suspiciousPrefix =
                        repetitionGuard.isSuspiciousPrefix(
                            contact.id,
                            partial.take(20)
                        )

                    if (!suspiciousPrefix) {
                        previewStarted = true
                        onStreaming(partial)
                    }
                } else {
                    onStreaming(partial)
                }
            }
        ).trim()

        if (output.isBlank()) {
            return null
        }

        val repeated =
            repetitionGuard.isRepetitive(
                contact.id,
                output
            )

        if (!repeated) {
            if (!previewStarted) {
                onStreaming(output)
            }
            return output
        }

        onStreaming("")

        // One regeneration attempt. If it is still repetitive,
        // silence is preferable to repeating the same line again.
        val replacement = gateway.chat(
            provider = contact.provider,
            model = model,
            systemPrompt =
                bundle.system +
                    """

                    【重复保护】
                    刚才生成的候选内容和你最近自己说过的话/主题过于相似。
                    这一次必须推进新信息、换自然角度，或简短回应后停住。
                    不要再次表达相同的“累、困、无聊、天气”等状态。
                    """.trimIndent(),
            userPrompt =
                bundle.user +
                    """

                    【最近已说内容，必须避开】
                    ${repetitionGuard.recentAssistantText(contact.id)}
                    """.trimIndent(),
            maxTokens = maxTokens
        ).trim()

        if (
            replacement.isBlank() ||
            repetitionGuard.isRepetitive(
                contact.id,
                replacement
            )
        ) {
            return null
        }

        onStreaming(replacement)
        return replacement
    }

    private fun isFastCasualMessage(text: String): Boolean {
        val clean = text.trim()
        if (clean.length > 42) return false

        val complexSignals = listOf(
            "详细", "分析", "解释", "为什么",
            "怎么做", "帮我", "代码", "方案",
            "比较", "总结", "原理", "论文",
            "计算", "证明"
        )

        return complexSignals.none {
            clean.contains(it)
        }
    }

    private fun naturalDelay(
        text: String,
        index: Int
    ): Long {
        val length = text.trim()
            .length
            .coerceAtLeast(1)

        val base = when {
            length <= 3 -> 280L
            length <= 10 -> 430L
            length <= 25 -> 700L
            length <= 60 -> 1000L
            else -> 1350L
        }

        return (
            base +
                stableJitter(
                    text.hashCode().toLong(),
                    320L
                ) +
                index * 220L
            ).coerceIn(240L, 2200L)
    }

    private fun stableJitter(
        seed: Long,
        range: Long
    ): Long {
        val positive = seed and 0x7fffffffL
        return positive % range.coerceAtLeast(1L)
    }

    private fun parseJson(raw: String): JsonObject? {
        val clean = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        runCatching {
            JsonParser.parseString(clean).asJsonObject
        }.getOrNull()?.let {
            return it
        }

        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')

        if (start >= 0 && end > start) {
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

    private fun parseTime(value: String): Long? {
        if (
            value.isBlank() ||
            value.equals("null", true)
        ) {
            return null
        }

        return try {
            OffsetDateTime.parse(value)
                .toInstant()
                .toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
