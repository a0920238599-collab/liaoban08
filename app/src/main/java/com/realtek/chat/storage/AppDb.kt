package com.realtek.chat.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AppDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "realtek_v3.db", null, 5) {

    private val crypto = CryptoBox()

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE contacts(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                subtitle TEXT NOT NULL,
                provider TEXT NOT NULL DEFAULT 'haijing',
                model TEXT NOT NULL,
                core_persona TEXT NOT NULL,
                style_rules TEXT NOT NULL,
                avatar_path TEXT,
                background_path TEXT,
                proactive_enabled INTEGER NOT NULL DEFAULT 1,
                last_proactive_at INTEGER
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE messages(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                contact_id INTEGER NOT NULL,
                role TEXT NOT NULL,
                type TEXT NOT NULL DEFAULT 'text',
                payload TEXT NOT NULL,
                media_path TEXT,
                mime_type TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(contact_id) REFERENCES contacts(id) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE memories(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                contact_id INTEGER NOT NULL,
                type TEXT NOT NULL,
                content TEXT NOT NULL,
                tags TEXT NOT NULL,
                importance REAL NOT NULL DEFAULT 0.5,
                confidence REAL NOT NULL DEFAULT 0.7,
                due_at INTEGER,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(contact_id) REFERENCES contacts(id) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE summaries(
                contact_id INTEGER PRIMARY KEY,
                summary TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(contact_id) REFERENCES contacts(id) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE social_state(
                contact_id INTEGER PRIMARY KEY,
                mood TEXT NOT NULL,
                conversation_energy REAL NOT NULL,
                social_drive REAL NOT NULL,
                current_topic TEXT NOT NULL,
                phase TEXT NOT NULL,
                familiarity REAL NOT NULL,
                trust REAL NOT NULL,
                humor_comfort REAL NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(contact_id) REFERENCES contacts(id) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE ai_topic_history(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                contact_id INTEGER NOT NULL,
                topic TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(contact_id) REFERENCES contacts(id) ON DELETE CASCADE
            )
        """.trimIndent())

        createV5Tables(db)

        addDefault(
            db,
            "小夏",
            "慢热、细心，聊天比较自然",
            "haijing",
            "grok-4.6",
            "你叫小夏。初始性格倾向：慢热、观察细、情绪稳定，偶尔会轻轻吐槽但不刻薄。与用户相处后，表达习惯可以逐渐调整。",
            "更偏即时聊天。消息数量跟随当下节奏，不固定一条或几条。用户没有要求方案时，不主动写列表或大段建议。"
        )

        addDefault(
            db,
            "阿深",
            "直接、机灵，偶尔冷幽默",
            "deepseek",
            "deepseek-flash",
            "你叫阿深。初始性格倾向：直接、机灵、冷幽默、说话不绕弯，同时会注意对方情绪。熟悉感和表达习惯会从共同聊天中逐渐形成。",
            "闲聊尽量简短，不机械复述用户原话。可以追问，可以开玩笑，避免模板化客服腔。"
        )

        addDefault(
            db,
            "G",
            "反应快、好奇、带一点调侃",
            "haijing",
            "grok-4.6",
            "你叫 G。初始性格倾向：反应快、好奇、幽默、略带调侃。会自然使用共同聊天记忆，但不会刻意展示记忆力。",
            "消息数量不固定，由当时的思维和对话节奏决定。可以先反应，也可以直接展开；真正复杂的问题再认真说。"
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (oldVersion < 2) {
            db.execSQL(
                "ALTER TABLE contacts " +
                    "ADD COLUMN provider TEXT NOT NULL DEFAULT 'haijing'"
            )
        }

        if (oldVersion < 3) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS social_state(
                    contact_id INTEGER PRIMARY KEY,
                    mood TEXT NOT NULL,
                    conversation_energy REAL NOT NULL,
                    social_drive REAL NOT NULL,
                    current_topic TEXT NOT NULL,
                    phase TEXT NOT NULL,
                    familiarity REAL NOT NULL,
                    trust REAL NOT NULL,
                    humor_comfort REAL NOT NULL,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY(contact_id) REFERENCES contacts(id) ON DELETE CASCADE
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS ai_topic_history(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    contact_id INTEGER NOT NULL,
                    topic TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY(contact_id) REFERENCES contacts(id) ON DELETE CASCADE
                )
            """.trimIndent())
        }

        if (oldVersion < 4) {
            db.execSQL(
                "UPDATE contacts SET provider='haijing' " +
                    "WHERE provider='runapi'"
            )
        }

        if (oldVersion < 5) {
            createV5Tables(db)
        }
    }

    private fun createV5Tables(
        db: SQLiteDatabase
    ) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS persona_state(
                contact_id INTEGER PRIMARY KEY,
                long_term_rules TEXT NOT NULL,
                temporary_rules TEXT NOT NULL,
                temporary_until INTEGER,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(contact_id) REFERENCES contacts(id) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS chat_groups(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS group_members(
                group_id INTEGER NOT NULL,
                contact_id INTEGER NOT NULL,
                joined_at INTEGER NOT NULL,
                PRIMARY KEY(group_id, contact_id),
                FOREIGN KEY(group_id) REFERENCES chat_groups(id) ON DELETE CASCADE,
                FOREIGN KEY(contact_id) REFERENCES contacts(id) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS group_messages(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                group_id INTEGER NOT NULL,
                sender_kind TEXT NOT NULL,
                sender_key TEXT NOT NULL,
                sender_contact_id INTEGER,
                sender_name TEXT NOT NULL,
                type TEXT NOT NULL DEFAULT 'text',
                payload TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(group_id) REFERENCES chat_groups(id) ON DELETE CASCADE,
                FOREIGN KEY(sender_contact_id) REFERENCES contacts(id) ON DELETE SET NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_group_messages_group_time
            ON group_messages(group_id, created_at, id)
        """.trimIndent())
    }

    private fun addDefault(
        db: SQLiteDatabase,
        name: String,
        subtitle: String,
        provider: String,
        model: String,
        persona: String,
        style: String
    ) {
        val v = ContentValues().apply {
            put("name", name)
            put("subtitle", subtitle)
            put("provider", provider)
            put("model", model)
            put("core_persona", crypto.encryptText(persona))
            put("style_rules", crypto.encryptText(style))
            put("proactive_enabled", 1)
        }
        db.insert("contacts", null, v)
    }

    fun addContact(
        name: String,
        subtitle: String,
        provider: String,
        model: String,
        persona: String,
        style: String,
        avatarPath: String?
    ): Int {
        val v = ContentValues().apply {
            put("name", name.trim())
            put("subtitle", subtitle.trim())
            put(
                "provider",
                if (provider.lowercase() == "deepseek")
                    "deepseek"
                else
                    "haijing"
            )
            put("model", model.trim())
            put("core_persona", crypto.encryptText(persona))
            put("style_rules", crypto.encryptText(style))
            if (avatarPath == null) putNull("avatar_path") else put("avatar_path", avatarPath)
            put("proactive_enabled", 1)
        }
        return writableDatabase.insert("contacts", null, v).toInt()
    }

    fun getContacts(): List<Contact> {
        val out = mutableListOf<Contact>()
        readableDatabase.rawQuery("SELECT * FROM contacts ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) out += contact(c)
        }
        return out
    }

    fun getContact(id: Int): Contact? {
        readableDatabase.rawQuery(
            "SELECT * FROM contacts WHERE id=? LIMIT 1",
            arrayOf(id.toString())
        ).use { c ->
            return if (c.moveToFirst()) contact(c) else null
        }
    }

    fun updateContact(contact: Contact) {
        val v = ContentValues().apply {
            put("name", contact.name.trim())
            put("subtitle", contact.subtitle.trim())
            put("provider", contact.provider)
            put("model", contact.model.trim())
            put("core_persona", crypto.encryptText(contact.corePersona))
            put("style_rules", crypto.encryptText(contact.styleRules))
            if (contact.avatarPath == null) putNull("avatar_path") else put("avatar_path", contact.avatarPath)
            if (contact.backgroundPath == null) putNull("background_path") else put("background_path", contact.backgroundPath)
            put("proactive_enabled", if (contact.proactiveEnabled) 1 else 0)
            if (contact.lastProactiveAt == null) putNull("last_proactive_at")
            else put("last_proactive_at", contact.lastProactiveAt)
        }
        writableDatabase.update("contacts", v, "id=?", arrayOf(contact.id.toString()))
    }

    fun deleteContact(id: Int): List<String> {
        val paths = mutableListOf<String>()

        getContact(id)?.let {
            it.avatarPath?.let(paths::add)
            it.backgroundPath?.let(paths::add)
        }

        readableDatabase.rawQuery(
            "SELECT media_path FROM messages WHERE contact_id=? AND media_path IS NOT NULL",
            arrayOf(id.toString())
        ).use { c ->
            while (c.moveToNext()) c.getString(0)?.let(paths::add)
        }

        writableDatabase.delete("contacts", "id=?", arrayOf(id.toString()))
        return paths.distinct()
    }

    fun addMessage(
        contactId: Int,
        role: String,
        type: String,
        text: String,
        mediaPath: String? = null,
        mimeType: String? = null
    ): Long {
        val v = ContentValues().apply {
            put("contact_id", contactId)
            put("role", role)
            put("type", type)
            put("payload", crypto.encryptText(text))
            if (mediaPath == null) putNull("media_path") else put("media_path", mediaPath)
            if (mimeType == null) putNull("mime_type") else put("mime_type", mimeType)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("messages", null, v)
    }

    fun getMessages(contactId: Int): List<ChatMessage> =
        queryMessages(
            "SELECT * FROM messages WHERE contact_id=? ORDER BY created_at ASC, id ASC",
            arrayOf(contactId.toString())
        )

    fun getRecentMessages(contactId: Int, limit: Int): List<ChatMessage> =
        queryMessages(
            "SELECT * FROM messages WHERE contact_id=? ORDER BY created_at DESC, id DESC LIMIT ?",
            arrayOf(contactId.toString(), limit.toString())
        ).reversed()

    fun getLastMessage(contactId: Int): ChatMessage? =
        getRecentMessages(contactId, 1).firstOrNull()

    fun userMessageCount(contactId: Int): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE contact_id=? AND role='user'",
            arrayOf(contactId.toString())
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun clearMessages(contactId: Int): List<String> {
        val paths = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT media_path FROM messages WHERE contact_id=? AND media_path IS NOT NULL",
            arrayOf(contactId.toString())
        ).use { c ->
            while (c.moveToNext()) c.getString(0)?.let(paths::add)
        }

        writableDatabase.delete("messages", "contact_id=?", arrayOf(contactId.toString()))
        return paths.distinct()
    }

    fun addMemory(
        contactId: Int,
        type: String,
        content: String,
        tags: String,
        importance: Double,
        confidence: Double,
        dueAt: Long?
    ) {
        val v = ContentValues().apply {
            put("contact_id", contactId)
            put("type", type)
            put("content", crypto.encryptText(content))
            put("tags", crypto.encryptText(tags))
            put("importance", importance)
            put("confidence", confidence)
            if (dueAt == null) putNull("due_at") else put("due_at", dueAt)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("memories", null, v)
    }

    fun memoryExists(contactId: Int, content: String): Boolean =
        getMemories(contactId, 300).any { it.content == content }

    fun getMemories(contactId: Int, limit: Int = 250): List<MemoryItem> {
        val out = mutableListOf<MemoryItem>()
        readableDatabase.rawQuery(
            "SELECT * FROM memories WHERE contact_id=? ORDER BY importance DESC, created_at DESC LIMIT ?",
            arrayOf(contactId.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val due = c.getColumnIndexOrThrow("due_at")
                out += MemoryItem(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    contactId = c.getInt(c.getColumnIndexOrThrow("contact_id")),
                    type = c.getString(c.getColumnIndexOrThrow("type")),
                    content = crypto.decryptText(c.getString(c.getColumnIndexOrThrow("content"))),
                    tags = crypto.decryptText(c.getString(c.getColumnIndexOrThrow("tags"))),
                    importance = c.getDouble(c.getColumnIndexOrThrow("importance")),
                    confidence = c.getDouble(c.getColumnIndexOrThrow("confidence")),
                    dueAt = if (c.isNull(due)) null else c.getLong(due),
                    createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                )
            }
        }
        return out
    }

    fun deleteMemory(id: Long) {
        writableDatabase.delete("memories", "id=?", arrayOf(id.toString()))
    }

    fun clearMemories(contactId: Int) {
        writableDatabase.delete("memories", "contact_id=?", arrayOf(contactId.toString()))
    }

    fun getSummary(contactId: Int): SummaryItem? {
        readableDatabase.rawQuery(
            "SELECT * FROM summaries WHERE contact_id=? LIMIT 1",
            arrayOf(contactId.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            return SummaryItem(
                contactId,
                crypto.decryptText(c.getString(c.getColumnIndexOrThrow("summary"))),
                c.getLong(c.getColumnIndexOrThrow("updated_at"))
            )
        }
    }

    fun upsertSummary(contactId: Int, summary: String) {
        val v = ContentValues().apply {
            put("contact_id", contactId)
            put("summary", crypto.encryptText(summary))
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "summaries", null, v, SQLiteDatabase.CONFLICT_REPLACE
        )
    }


    fun getSocialState(contactId: Int): SocialState? {
        readableDatabase.rawQuery(
            "SELECT * FROM social_state WHERE contact_id=? LIMIT 1",
            arrayOf(contactId.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null

            return SocialState(
                contactId = contactId,
                mood = crypto.decryptText(
                    c.getString(
                        c.getColumnIndexOrThrow("mood")
                    )
                ),
                conversationEnergy = c.getDouble(
                    c.getColumnIndexOrThrow(
                        "conversation_energy"
                    )
                ),
                socialDrive = c.getDouble(
                    c.getColumnIndexOrThrow(
                        "social_drive"
                    )
                ),
                currentTopic = crypto.decryptText(
                    c.getString(
                        c.getColumnIndexOrThrow(
                            "current_topic"
                        )
                    )
                ),
                phase = c.getString(
                    c.getColumnIndexOrThrow("phase")
                ),
                familiarity = c.getDouble(
                    c.getColumnIndexOrThrow(
                        "familiarity"
                    )
                ),
                trust = c.getDouble(
                    c.getColumnIndexOrThrow("trust")
                ),
                humorComfort = c.getDouble(
                    c.getColumnIndexOrThrow(
                        "humor_comfort"
                    )
                ),
                updatedAt = c.getLong(
                    c.getColumnIndexOrThrow(
                        "updated_at"
                    )
                )
            )
        }
    }

    fun upsertSocialState(state: SocialState) {
        val values = ContentValues().apply {
            put("contact_id", state.contactId)
            put(
                "mood",
                crypto.encryptText(state.mood)
            )
            put(
                "conversation_energy",
                state.conversationEnergy
            )
            put(
                "social_drive",
                state.socialDrive
            )
            put(
                "current_topic",
                crypto.encryptText(
                    state.currentTopic
                )
            )
            put("phase", state.phase)
            put(
                "familiarity",
                state.familiarity
            )
            put(
                "trust",
                state.trust
            )
            put(
                "humor_comfort",
                state.humorComfort
            )
            put(
                "updated_at",
                state.updatedAt
            )
        }

        writableDatabase.insertWithOnConflict(
            "social_state",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun addSelfTopic(
        contactId: Int,
        topic: String
    ) {
        val clean = topic.trim()
        if (clean.isBlank()) return

        val values = ContentValues().apply {
            put("contact_id", contactId)
            put(
                "topic",
                crypto.encryptText(clean)
            )
            put(
                "created_at",
                System.currentTimeMillis()
            )
        }

        writableDatabase.insert(
            "ai_topic_history",
            null,
            values
        )

        // Keep the history bounded.
        writableDatabase.execSQL(
            """
            DELETE FROM ai_topic_history
            WHERE contact_id=?
              AND id NOT IN (
                  SELECT id
                  FROM ai_topic_history
                  WHERE contact_id=?
                  ORDER BY created_at DESC
                  LIMIT 60
              )
            """.trimIndent(),
            arrayOf<Any>(
                contactId,
                contactId
            )
        )
    }

    fun getRecentSelfTopics(
        contactId: Int,
        limit: Int = 16
    ): List<SelfTopic> {
        val out = mutableListOf<SelfTopic>()

        readableDatabase.rawQuery(
            """
            SELECT * FROM ai_topic_history
            WHERE contact_id=?
            ORDER BY created_at DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                contactId.toString(),
                limit.toString()
            )
        ).use { c ->
            while (c.moveToNext()) {
                out += SelfTopic(
                    id = c.getLong(
                        c.getColumnIndexOrThrow("id")
                    ),
                    contactId = c.getInt(
                        c.getColumnIndexOrThrow(
                            "contact_id"
                        )
                    ),
                    topic = crypto.decryptText(
                        c.getString(
                            c.getColumnIndexOrThrow(
                                "topic"
                            )
                        )
                    ),
                    createdAt = c.getLong(
                        c.getColumnIndexOrThrow(
                            "created_at"
                        )
                    )
                )
            }
        }

        return out
    }

fun getPersonaState(
    contactId: Int
): PersonaState? {
    readableDatabase.rawQuery(
        "SELECT * FROM persona_state WHERE contact_id=? LIMIT 1",
        arrayOf(contactId.toString())
    ).use { c ->
        if (!c.moveToFirst()) return null

        val tempUntilIndex =
            c.getColumnIndexOrThrow(
                "temporary_until"
            )

        return PersonaState(
            contactId = contactId,
            longTermRules = crypto.decryptText(
                c.getString(
                    c.getColumnIndexOrThrow(
                        "long_term_rules"
                    )
                )
            ),
            temporaryRules = crypto.decryptText(
                c.getString(
                    c.getColumnIndexOrThrow(
                        "temporary_rules"
                    )
                )
            ),
            temporaryUntil =
                if (
                    c.isNull(
                        tempUntilIndex
                    )
                ) null
                else c.getLong(
                    tempUntilIndex
                ),
            updatedAt = c.getLong(
                c.getColumnIndexOrThrow(
                    "updated_at"
                )
            )
        )
    }
}

fun upsertPersonaState(
    state: PersonaState
) {
    val values = ContentValues().apply {
        put(
            "contact_id",
            state.contactId
        )
        put(
            "long_term_rules",
            crypto.encryptText(
                state.longTermRules
            )
        )
        put(
            "temporary_rules",
            crypto.encryptText(
                state.temporaryRules
            )
        )
        if (
            state.temporaryUntil == null
        ) {
            putNull(
                "temporary_until"
            )
        } else {
            put(
                "temporary_until",
                state.temporaryUntil
            )
        }
        put(
            "updated_at",
            state.updatedAt
        )
    }

    writableDatabase.insertWithOnConflict(
        "persona_state",
        null,
        values,
        SQLiteDatabase.CONFLICT_REPLACE
    )
}


fun clearPersonaState(
    contactId: Int
) {
    writableDatabase.delete(
        "persona_state",
        "contact_id=?",
        arrayOf(
            contactId.toString()
        )
    )
}


fun addGroup(
    name: String,
    memberIds: List<Int>
): Int {
    require(
        memberIds.distinct().size >= 2
    ) {
        "群聊至少需要两个联系人"
    }

    val db = writableDatabase
    db.beginTransaction()

    try {
        val values =
            ContentValues().apply {
                put(
                    "name",
                    name.trim()
                        .ifBlank {
                            "群聊"
                        }
                )
                put(
                    "created_at",
                    System.currentTimeMillis()
                )
            }

        val groupId =
            db.insert(
                "chat_groups",
                null,
                values
            ).toInt()

        require(groupId > 0)

        val now =
            System.currentTimeMillis()

        memberIds.distinct()
            .forEach {
                contactId ->
                val memberValues =
                    ContentValues().apply {
                        put(
                            "group_id",
                            groupId
                        )
                        put(
                            "contact_id",
                            contactId
                        )
                        put(
                            "joined_at",
                            now
                        )
                    }

                db.insertOrThrow(
                    "group_members",
                    null,
                    memberValues
                )
            }

        db.setTransactionSuccessful()
        return groupId
    } finally {
        db.endTransaction()
    }
}

fun getGroups(): List<ChatGroup> {
    val out =
        mutableListOf<ChatGroup>()

    readableDatabase.rawQuery(
        """
        SELECT * FROM chat_groups
        ORDER BY created_at DESC, id DESC
        """.trimIndent(),
        null
    ).use {
        c ->
        while (c.moveToNext()) {
            out += ChatGroup(
                id = c.getInt(
                    c.getColumnIndexOrThrow(
                        "id"
                    )
                ),
                name = c.getString(
                    c.getColumnIndexOrThrow(
                        "name"
                    )
                ),
                createdAt =
                    c.getLong(
                        c.getColumnIndexOrThrow(
                            "created_at"
                        )
                    )
            )
        }
    }

    return out
}

fun getGroup(
    groupId: Int
): ChatGroup? {
    readableDatabase.rawQuery(
        """
        SELECT * FROM chat_groups
        WHERE id=?
        LIMIT 1
        """.trimIndent(),
        arrayOf(
            groupId.toString()
        )
    ).use {
        c ->
        if (!c.moveToFirst()) {
            return null
        }

        return ChatGroup(
            id = c.getInt(
                c.getColumnIndexOrThrow(
                    "id"
                )
            ),
            name = c.getString(
                c.getColumnIndexOrThrow(
                    "name"
                )
            ),
            createdAt =
                c.getLong(
                    c.getColumnIndexOrThrow(
                        "created_at"
                    )
                )
        )
    }
}

fun getGroupMembers(
    groupId: Int
): List<Contact> {
    val out =
        mutableListOf<Contact>()

    readableDatabase.rawQuery(
        """
        SELECT c.*
        FROM contacts c
        INNER JOIN group_members gm
            ON gm.contact_id=c.id
        WHERE gm.group_id=?
        ORDER BY gm.joined_at ASC, c.id ASC
        """.trimIndent(),
        arrayOf(
            groupId.toString()
        )
    ).use {
        c ->
        while (c.moveToNext()) {
            out += contact(c)
        }
    }

    return out
}

fun setGroupMembers(
    groupId: Int,
    memberIds: List<Int>
) {
    require(
        memberIds.distinct().size >= 2
    ) {
        "群聊至少需要两个联系人"
    }

    val db = writableDatabase
    db.beginTransaction()

    try {
        db.delete(
            "group_members",
            "group_id=?",
            arrayOf(
                groupId.toString()
            )
        )

        val now =
            System.currentTimeMillis()

        memberIds.distinct()
            .forEach {
                contactId ->
                val values =
                    ContentValues().apply {
                        put(
                            "group_id",
                            groupId
                        )
                        put(
                            "contact_id",
                            contactId
                        )
                        put(
                            "joined_at",
                            now
                        )
                    }

                db.insertOrThrow(
                    "group_members",
                    null,
                    values
                )
            }

        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

fun renameGroup(
    groupId: Int,
    name: String
) {
    val values =
        ContentValues().apply {
            put(
                "name",
                name.trim()
                    .ifBlank {
                        "群聊"
                    }
            )
        }

    writableDatabase.update(
        "chat_groups",
        values,
        "id=?",
        arrayOf(
            groupId.toString()
        )
    )
}

fun deleteGroup(
    groupId: Int
) {
    writableDatabase.delete(
        "chat_groups",
        "id=?",
        arrayOf(
            groupId.toString()
        )
    )
}

fun addGroupMessage(
    groupId: Int,
    senderKind: String,
    senderContactId: Int?,
    senderName: String,
    type: String,
    text: String
): Long {
    require(
        senderKind == "user" ||
            senderKind == "contact"
    )

    val values =
        ContentValues().apply {
            put(
                "group_id",
                groupId
            )
            put(
                "sender_kind",
                senderKind
            )
            put(
                "sender_key",
                if (
                    senderKind == "user"
                ) {
                    "user"
                } else {
                    "contact:${senderContactId ?: -1}"
                }
            )

            if (
                senderContactId == null
            ) {
                putNull(
                    "sender_contact_id"
                )
            } else {
                put(
                    "sender_contact_id",
                    senderContactId
                )
            }

            put(
                "sender_name",
                senderName
            )
            put(
                "type",
                type
            )
            put(
                "payload",
                crypto.encryptText(
                    text
                )
            )
            put(
                "created_at",
                System.currentTimeMillis()
            )
        }

    return writableDatabase.insert(
        "group_messages",
        null,
        values
    )
}

fun getGroupMessages(
    groupId: Int
): List<GroupMessage> =
    queryGroupMessages(
        """
        SELECT * FROM group_messages
        WHERE group_id=?
        ORDER BY created_at ASC, id ASC
        """.trimIndent(),
        arrayOf(
            groupId.toString()
        )
    )

fun getRecentGroupMessages(
    groupId: Int,
    limit: Int
): List<GroupMessage> =
    queryGroupMessages(
        """
        SELECT * FROM group_messages
        WHERE group_id=?
        ORDER BY created_at DESC, id DESC
        LIMIT ?
        """.trimIndent(),
        arrayOf(
            groupId.toString(),
            limit.toString()
        )
    ).reversed()

fun getLastGroupMessage(
    groupId: Int
): GroupMessage? =
    getRecentGroupMessages(
        groupId,
        1
    ).firstOrNull()

private fun queryGroupMessages(
    sql: String,
    args: Array<String>
): List<GroupMessage> {
    val out =
        mutableListOf<GroupMessage>()

    readableDatabase.rawQuery(
        sql,
        args
    ).use {
        c ->
        while (c.moveToNext()) {
            val senderIdIndex =
                c.getColumnIndexOrThrow(
                    "sender_contact_id"
                )

            out += GroupMessage(
                id = c.getLong(
                    c.getColumnIndexOrThrow(
                        "id"
                    )
                ),
                groupId = c.getInt(
                    c.getColumnIndexOrThrow(
                        "group_id"
                    )
                ),
                senderKind =
                    c.getString(
                        c.getColumnIndexOrThrow(
                            "sender_kind"
                        )
                    ),
                senderKey =
                    c.getString(
                        c.getColumnIndexOrThrow(
                            "sender_key"
                        )
                    ),
                senderContactId =
                    if (
                        c.isNull(
                            senderIdIndex
                        )
                    ) null
                    else c.getInt(
                        senderIdIndex
                    ),
                senderName =
                    c.getString(
                        c.getColumnIndexOrThrow(
                            "sender_name"
                        )
                    ),
                type = c.getString(
                    c.getColumnIndexOrThrow(
                        "type"
                    )
                ),
                text = crypto.decryptText(
                    c.getString(
                        c.getColumnIndexOrThrow(
                            "payload"
                        )
                    )
                ),
                createdAt =
                    c.getLong(
                        c.getColumnIndexOrThrow(
                            "created_at"
                        )
                    )
            )
        }
    }

    return out
}

    private fun queryMessages(sql: String, args: Array<String>): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out += ChatMessage(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    contactId = c.getInt(c.getColumnIndexOrThrow("contact_id")),
                    role = c.getString(c.getColumnIndexOrThrow("role")),
                    type = c.getString(c.getColumnIndexOrThrow("type")),
                    text = crypto.decryptText(c.getString(c.getColumnIndexOrThrow("payload"))),
                    mediaPath = c.strOrNull("media_path"),
                    mimeType = c.strOrNull("mime_type"),
                    createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                )
            }
        }
        return out
    }

    private fun contact(c: Cursor): Contact {
        val last = c.getColumnIndexOrThrow("last_proactive_at")
        return Contact(
            id = c.getInt(c.getColumnIndexOrThrow("id")),
            name = c.getString(c.getColumnIndexOrThrow("name")),
            subtitle = c.getString(c.getColumnIndexOrThrow("subtitle")),
            provider = c.getString(c.getColumnIndexOrThrow("provider")),
            model = c.getString(c.getColumnIndexOrThrow("model")),
            corePersona = crypto.decryptText(c.getString(c.getColumnIndexOrThrow("core_persona"))),
            styleRules = crypto.decryptText(c.getString(c.getColumnIndexOrThrow("style_rules"))),
            avatarPath = c.strOrNull("avatar_path"),
            backgroundPath = c.strOrNull("background_path"),
            proactiveEnabled = c.getInt(c.getColumnIndexOrThrow("proactive_enabled")) == 1,
            lastProactiveAt = if (c.isNull(last)) null else c.getLong(last)
        )
    }

    private fun Cursor.strOrNull(name: String): String? {
        val i = getColumnIndexOrThrow(name)
        return if (isNull(i)) null else getString(i)
    }

    companion object {
        @Volatile private var INSTANCE: AppDb? = null
        fun get(context: Context): AppDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppDb(context).also { INSTANCE = it }
            }
    }
}
