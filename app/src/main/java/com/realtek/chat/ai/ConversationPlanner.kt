package com.realtek.chat.ai

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.realtek.chat.settings.AppSettings
import com.realtek.chat.storage.AppDb
import com.realtek.chat.storage.Contact
import com.realtek.chat.storage.MemoryItem
import kotlin.math.abs

data class ConversationPlan(
    val mode: String,
    val responseStyle: String,
    val askFollowup: Boolean,
    val selectedMemoryIds: List<Long>,
    val usedSemanticPlanner: Boolean,
    val topicLabel: String = ""
)

class ConversationPlanner(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDb.get(appContext)
    private val settings = AppSettings(appContext)
    private val gateway = RunApiGateway(appContext)

    suspend fun plan(
        contact: Contact,
        currentText: String,
        fastMode: Boolean
    ): ConversationPlan {
        val local = localPlan(currentText, fastMode)
        val memories = db.getMemories(contact.id, 250)

        if (memories.isEmpty()) return local

        val shouldSemanticRefine =
            needsSemanticMemory(currentText) ||
                currentText.length > 48 ||
                local.mode == "repair" ||
                local.mode == "talk_burst"

        if (!shouldSemanticRefine) {
            val lightweight = lightweightMemorySelection(currentText, memories)
            return local.copy(selectedMemoryIds = lightweight.map { it.id })
        }

        val candidates = memoryCandidates(currentText, memories)
        if (candidates.isEmpty()) return local

        return runCatching {
            semanticRefine(
                contact = contact,
                currentText = currentText,
                local = local,
                candidates = candidates
            )
        }.getOrElse {
            local.copy(
                selectedMemoryIds = lightweightMemorySelection(
                    currentText,
                    candidates
                ).map { it.id }
            )
        }
    }

    fun resolveMemories(
        contactId: Int,
        plan: ConversationPlan
    ): List<MemoryItem> {
        if (plan.selectedMemoryIds.isEmpty()) return emptyList()
        val wanted = plan.selectedMemoryIds.toSet()
        return db.getMemories(contactId, 250)
            .filter { it.id in wanted }
            .take(8)
    }

private fun localPlan(
    text: String,
    fastMode: Boolean
): ConversationPlan {
    val clean =
        text.trim()

    val talkSignals =
        listOf(
            "你继续说",
            "继续说",
            "你多说点",
            "多说点",
            "你说一会",
            "你来讲",
            "你讲讲",
            "讲给我听",
            "一直说",
            "你先说",
            "你说吧",
            "再说点",
            "继续讲",
            "接着说",
            "讲个故事",
            "跟我说说",
            "陪我聊会儿",
            "我想听你说",
            "你随便说",
            "多聊会儿"
        )

    val repairSignals =
        listOf(
            "不是这个意思",
            "你理解错",
            "你搞错",
            "不是这样",
            "我不是说",
            "你没懂",
            "不是啊"
        )

    val emotionSignals =
        listOf(
            "气死",
            "烦死",
            "烦",
            "累死",
            "好累",
            "难受",
            "离谱",
            "笑死",
            "开心",
            "崩溃",
            "无语",
            "郁闷"
        )

    val questionSignals =
        listOf(
            "为什么",
            "怎么",
            "怎么办",
            "什么",
            "哪种",
            "哪个",
            "多少",
            "能不能",
            "可以吗",
            "是不是"
        )

    val mode: String
    val style: String
    val ask: Boolean

    when {
        talkSignals.any {
            clean.contains(it)
        } -> {
            mode = "talk_burst"
            style =
                "用户想听你继续说。先自然说当前这一条；之后是否再说由社交行为层逐条重新判断，不预设总条数。"
            ask = false
        }

        repairSignals.any {
            clean.contains(it)
        } -> {
            mode = "repair"
            style =
                "重新对齐理解，不辩解，不用固定道歉模板。"
            ask = false
        }

        clean.length <= 6 &&
            !clean.contains("?") &&
            !clean.contains("？") -> {
            mode = "backchannel"
            style =
                "自然即时回应。可以很短，也可以顺势说自己的内容；不要因为用户可能还有后文就自动沉默。"
            ask = false
        }

        emotionSignals.any {
            clean.contains(it)
        } -> {
            mode = "react"
            style =
                "先对事情或情绪本身产生真实反应；是否分析、追问或继续分享由上下文决定。"
            ask = false
        }

        clean.contains("?") ||
            clean.contains("？") ||
            questionSignals.any {
                clean.contains(it)
            } -> {
            mode = "answer"
            style =
                if (fastMode) {
                    "直接回答，保持即时聊天感。"
                } else {
                    "清楚回答当前问题；需要展开时自然展开，不做固定分段流程。"
                }
            ask = false
        }

        clean.length > 120 -> {
            mode = "respond_to_long"
            style =
                "抓住真正值得回应的内容，不需要逐点复述，也不用因为用户可能继续说就等待。"
            ask = false
        }

        else -> {
            mode = "casual"
            style =
                "自然回应。可以反应、表达观点、分享、追问或继续说，不使用固定问答结构。"
            ask = false
        }
    }

    return ConversationPlan(
        mode = mode,
        responseStyle = style,
        askFollowup = ask,
        selectedMemoryIds =
            emptyList(),
        usedSemanticPlanner =
            false,
        topicLabel =
            inferLocalTopic(
                clean
            )
    )
}

    private fun needsSemanticMemory(text: String): Boolean {
        val cues = listOf(
            "上次", "之前", "那个", "那件事", "后来", "结果",
            "终于", "又", "还是", "还记得", "你记得", "明天",
            "昨天", "今天", "这次", "那个人", "他", "她", "它",
            "当时", "之前说", "后面", "后来呢"
        )
        return cues.any { text.contains(it) }
    }

    private fun memoryCandidates(
        text: String,
        memories: List<MemoryItem>
    ): List<MemoryItem> {
        val now = System.currentTimeMillis()
        val picked = LinkedHashMap<Long, MemoryItem>()

        fun add(items: List<MemoryItem>) {
            for (m in items) {
                if (picked.size >= 40) break
                picked.putIfAbsent(m.id, m)
            }
        }

        // 重要记忆
        add(memories.sortedByDescending { it.importance }.take(12))

        // 最近记忆
        add(memories.sortedByDescending { it.createdAt }.take(12))

        // 未完成话题优先
        add(
            memories.filter { it.type == "open_loop" }
                .sortedByDescending { it.createdAt }
                .take(8)
        )

        // 临近事件优先
        add(
            memories.filter { m ->
                m.dueAt?.let {
                    abs(it - now) <= 7L * 24L * 3_600_000L
                } == true
            }
                .sortedBy { abs((it.dueAt ?: now) - now) }
                .take(8)
        )

        // 文本上有线索的也纳入候选，之后由模型做真正语义重排
        add(lightweightMemorySelection(text, memories).take(12))

        return picked.values.toList()
    }

    private fun lightweightMemorySelection(
        query: String,
        memories: List<MemoryItem>
    ): List<MemoryItem> {
        val queryTokens = tokenSet(query)
        val now = System.currentTimeMillis()

        return memories.sortedByDescending { m ->
            val memoryTokens = tokenSet(m.content + " " + m.tags)
            val overlap = queryTokens.intersect(memoryTokens).size.toDouble()
            val recentDays = (now - m.createdAt).coerceAtLeast(0) / 86_400_000.0
            val recency = 1.0 / (1.0 + recentDays / 30.0)
            val dueBoost = m.dueAt?.let {
                if (abs(it - now) <= 72 * 3_600_000L) 3.0 else 0.0
            } ?: 0.0

            overlap * 2.5 +
                m.importance * 1.5 +
                recency +
                dueBoost +
                if (m.type == "open_loop") 1.8 else 0.0
        }
    }

private suspend fun semanticRefine(
    contact: Contact,
    currentText: String,
    local: ConversationPlan,
    candidates: List<MemoryItem>
): ConversationPlan {
    val recent =
        db.getRecentMessages(
            contact.id,
            8
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

    val memoryList =
        candidates.joinToString("\n") {
            "[${it.id}] (${it.type}) ${it.content}" +
                if (
                    it.tags.isNotBlank()
                ) {
                    " | 关联词:${it.tags}"
                } else {
                    ""
                }
        }

    val recentSelfTopics =
        db.getRecentSelfTopics(
            contact.id,
            12
        )
            .map {
                it.topic
            }
            .distinct()
            .take(8)
            .joinToString("、")
            .ifBlank {
                "暂无"
            }

    val raw =
        gateway.chat(
            provider =
                settings.systemProvider,
            model =
                effectivePlannerModel(),
            systemPrompt = """
                你是聊天上下文规划器，不负责最终说话。

                你只做三件事：
                1. 判断当前消息更接近哪种聊天行为；
                2. 从候选长期记忆中按语义选出真正相关的内容；
                3. 给最终联系人模型一条简短的表达方向。

                不存在“第一阶段/第二阶段”“刚认识阶段/熟悉阶段”
                或“谁必须持有话语权”的固定流程。
                是否继续发下一条由后续社交行为层逐条判断。
            """.trimIndent(),
            userPrompt = """
                联系人：${contact.name}
                本地初步模式：${local.mode}

                最近聊天：
                $transcript

                当前消息：
                $currentText

                候选记忆：
                $memoryList

                联系人最近自己已经聊过的主题：
                $recentSelfTopics

                只输出 JSON：
                {
                  "mode":"casual/react/answer/repair/talk_burst/respond_to_long/backchannel",
                  "response_style":"一句简短行为方向",
                  "ask_followup":false,
                  "selected_memory_ids":[1,2],
                  "topic_label":"当前真正话题的简短标签"
                }

                selected_memory_ids 最多 8 条。
                不要因为用户看起来还想继续输入就要求联系人等待。
            """.trimIndent(),
            maxTokens = 190
        )

    val obj =
        parseJson(raw)
            ?: return local

    val ids =
        obj.getAsJsonArray(
            "selected_memory_ids"
        )
            ?.mapNotNull {
                runCatching {
                    it.asLong
                }.getOrNull()
            }
            ?.filter {
                id ->
                candidates.any {
                    it.id == id
                }
            }
            ?.distinct()
            ?.take(8)
            .orEmpty()

    val mode =
        obj["mode"]
            ?.takeIf {
                it.isJsonPrimitive
            }
            ?.asString
            ?.takeIf {
                it.isNotBlank()
            }
            ?: local.mode

    return local.copy(
        mode = mode,
        responseStyle =
            obj["response_style"]
                ?.takeIf {
                    it.isJsonPrimitive
                }
                ?.asString
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: local.responseStyle,
        askFollowup =
            obj["ask_followup"]
                ?.takeIf {
                    it.isJsonPrimitive
                }
                ?.asBoolean
                ?: local.askFollowup,
        selectedMemoryIds =
            ids,
        usedSemanticPlanner =
            true,
        topicLabel =
            obj["topic_label"]
                ?.takeIf {
                    it.isJsonPrimitive
                }
                ?.asString
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: local.topicLabel
    )
}

    private fun inferLocalTopic(text: String): String {
        val clean = text.trim()

        val keepPreviousTopic = listOf(
            "嗯",
            "嗯嗯",
            "啊",
            "哦",
            "哈哈",
            "哈哈哈",
            "真的",
            "是吗",
            "然后呢",
            "后来呢",
            "行",
            "好吧",
            "知道了"
        )

        if (
            keepPreviousTopic.any {
                clean == it
            }
        ) {
            return ""
        }

        return when {
            clean.contains("老师") -> "老师相关"
            clean.contains("同事") -> "同事相关"
            clean.contains("考试") -> "考试"
            clean.contains("面试") -> "面试"
            clean.contains("答辩") -> "答辩"
            clean.contains("工作") -> "工作"
            clean.contains("学校") || clean.contains("上课") -> "学校/课程"
            clean.contains("游戏") -> "游戏"
            clean.contains("电影") || clean.contains("剧") -> "影视"
            clean.contains("吃") || clean.contains("饭") -> "吃饭"
            clean.contains("睡") || clean.contains("困") -> "睡眠"
            clean.contains("累") -> "疲惫"
            clean.length <= 10 -> clean
            else -> clean.take(18)
        }
    }

    private fun effectivePlannerModel(): String =
        settings.plannerModel.ifBlank {
            settings.fastChatModel.ifBlank {
                settings.defaultModelFor(
                    settings.systemProvider
                )
            }
        }

    private fun tokenSet(text: String): Set<String> {
        val clean = text.lowercase()
            .replace(Regex("[\\p{Punct}\\s，。！？；：“”‘’（）【】]+"), "")

        val grams = if (clean.length >= 2) {
            (0 until clean.length - 1).map {
                clean.substring(it, it + 2)
            }
        } else {
            listOf(clean)
        }

        return grams.filter { it.isNotBlank() }.toSet()
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
        }.getOrNull()?.let { return it }

        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return runCatching {
                JsonParser.parseString(
                    clean.substring(start, end + 1)
                ).asJsonObject
            }.getOrNull()
        }

        return null
    }
}
