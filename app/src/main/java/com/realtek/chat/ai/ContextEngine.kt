package com.realtek.chat.ai

import android.content.Context
import com.realtek.chat.storage.AppDb
import com.realtek.chat.storage.Contact
import com.realtek.chat.storage.MemoryItem
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class PromptBundle(
    val system: String,
    val user: String
)

class ContextEngine(context: Context) {
    private val db = AppDb.get(context.applicationContext)
    private val personaEvolution =
        PersonaEvolutionEngine(
            context.applicationContext
        )

    fun build(
        contact: Contact,
        currentText: String,
        fastMode: Boolean,
        plan: ConversationPlan,
        selectedMemories: List<MemoryItem>,
        socialState: com.realtek.chat.storage.SocialState,
        recentSelfTopics: String,
        recentAssistantText: String
    ): PromptBundle {
        val recentAll = db.getRecentMessages(
            contact.id,
            if (fastMode) 8 else 14
        )

        // 当前消息已经先写入数据库，因此从“最近聊天”中移除它，
        // 避免同一句同时出现在历史和【最新消息】里。
        val recent = if (
            recentAll.isNotEmpty() &&
            recentAll.last().role == "user" &&
            (
                recentAll.last().text == currentText ||
                    (
                        recentAll.last().type == "image" &&
                            currentText.contains("图片")
                        )
                )
        ) {
            recentAll.dropLast(1)
        } else {
            recentAll
        }.takeLast(if (fastMode) 6 else 12)

        val summary = db.getSummary(contact.id)?.summary.orEmpty()

        val adaptivePersona =
            personaEvolution.effectiveRules(
                contact.id
            )

        val transcript = recent.joinToString("\n") {
            "${if (it.role == "user") "用户" else contact.name}：" +
                if (it.type == "image") "[图片]" else it.text
        }


        val now =
            System.currentTimeMillis()

        val previousMessage =
            recent.lastOrNull()

        val gapMinutes =
            previousMessage?.let {
                (
                    now - it.createdAt
                    ).coerceAtLeast(0L) /
                    60_000L
            }

        val currentClock =
            LocalDateTime.now().format(
                DateTimeFormatter.ofPattern(
                    "yyyy-MM-dd HH:mm"
                )
            )

        val recentUserCount =
            recent.count {
                it.role == "user"
            }

        val recentAssistantCount =
            recent.count {
                it.role == "assistant"
            }

        val assistantQuestionCount =
            recent.count {
                it.role == "assistant" &&
                    (
                        it.text.contains("?") ||
                            it.text.contains("？")
                        )
            }

        val burdenSummary =
            "最近${recent.size}条中：用户${recentUserCount}条，" +
                "联系人${recentAssistantCount}条，" +
                "联系人问句${assistantQuestionCount}条。"

        val memoryText = selectedMemories
            .joinToString("\n") {
                "- (${it.type}) ${it.content}"
            }
            .ifBlank { "暂无" }

        val system = """
            你正在作为联系人“${contact.name}”与用户进行即时聊天。

            【初始核心性格：只是人格种子，不是永久不可覆盖的命令】
            ${contact.corePersona}

            【初始说话方式】
            ${contact.styleRules}

            【与当前用户相处后形成的动态人格/聊天习惯】
            $adaptivePersona

            优先级：
            用户当前明确反馈
            > 上面的动态人格/相处规则
            > 当前聊天状态
            > 初始说话方式
            > 初始核心性格中与表达风格有关的部分

            如果动态规则与初始“爱用表情、爱追问、话多/话少”等表达习惯冲突，
            以动态规则为准。
            初始人物卡里任何“每次都、固定几条、必须结尾加表情、总要追问”之类写死的动作，
            只当作很弱的初始倾向，不能机械执行。

            【关系理解】
            关系直接从初始人物设定、动态人格、共享记忆和真实聊天上下文中理解。
            不根据创建时间或聊天条数改变关系。
            人物设定是什么关系，就从那个关系自然交流。

            【Conversation Planner 参考】
            mode = ${plan.mode}
            response_style = ${plan.responseStyle}
            ask_followup = ${plan.askFollowup}
            topic = ${plan.topicLabel}

            【持续聊天状态】
            mood = ${socialState.mood}
            conversation_energy = ${"%.2f".format(socialState.conversationEnergy)}
            social_drive = ${"%.2f".format(socialState.socialDrive)}
            current_topic = ${socialState.currentTopic.ifBlank { "暂无" }}

            这些只是连续聊天信号，不定义关系身份，也不是现实身体状态。
            不要因为 energy 低就编造“我好累、我困、我刚下班”等现实身体或线下经历。

            【最近已经聊过的自我主题/冷却】
            $recentSelfTopics

            【最近你自己已经说过的话】
            $recentAssistantText

            【近期摘要】
            ${summary.ifBlank { "暂无" }}

            【这一轮语义上真正相关的长期记忆】
            $memoryText

            【当前时间与节奏】
            当前设备时间：$currentClock
            距离上一条可见上下文消息：
            ${gapMinutes?.let { "${it} 分钟" } ?: "未知"}
            $burdenSummary

            ${SocialConstitution.runtimeRules}

            【本轮补充要求】
            - Planner 只提供 mode/topic/表达风格参考，不负责推进任何固定流程。
            - ${if (fastMode) "这是快速闲聊路径：优先快、短、自然，但不要敷衍。" else "正常质量路径：保持自然，同时保证内容质量。"}
            - 如果共享背景已经建立，直接沿用，不要重新解释来源。
            - 不要因为推测“用户可能还要继续说”就自动闭嘴、等待或让出话轮。
              这是异步文字聊天，双方消息可以交叉；你有自然想说的内容就正常说。

        """.trimIndent()

        val user = """
            【最近聊天】
            ${transcript.ifBlank { "暂无" }}

            【最新消息】
            $currentText

            直接自然回应当前聊天。

            只输出“这一条聊天消息本身”。
            不要 JSON，不要 Markdown 代码块，不要标注角色，不要解释内部决策。
        """.trimIndent()

        return PromptBundle(system, user)
    }
}
