package com.realtek.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.realtek.chat.ai.GroupChatEngine
import com.realtek.chat.storage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CreateGroupScreen(
    onBack: () -> Unit,
    onCreated: (Int) -> Unit
) {
    val context =
        LocalContext.current

    val db =
        remember {
            AppDb.get(context)
        }

    val scope =
        rememberCoroutineScope()

    var contacts by remember {
        mutableStateOf(
            emptyList<Contact>()
        )
    }

    var name by remember {
        mutableStateOf("")
    }

    var selected by remember {
        mutableStateOf(
            emptySet<Int>()
        )
    }

    var error by remember {
        mutableStateOf("")
    }

    LaunchedEffect(Unit) {
        contacts =
            withContext(
                Dispatchers.IO
            ) {
                db.getContacts()
                    .sortedBy {
                        it.name
                    }
            }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(PageGray)
    ) {
        TopBar(
            title = "发起群聊",
            onBack = onBack,
            right = {
                TopTextAction(
                    text = "完成",
                    enabled =
                        selected.size >= 2,
                    onClick = {
                        val ids =
                            selected.toList()

                        val groupName =
                            name.trim()
                                .ifBlank {
                                    contacts
                                        .filter {
                                            it.id in
                                                selected
                                        }
                                        .take(3)
                                        .joinToString("、") {
                                            it.name
                                        }
                                        .ifBlank {
                                            "群聊"
                                        }
                                }

                        scope.launch {
                            runCatching {
                                withContext(
                                    Dispatchers.IO
                                ) {
                                    db.addGroup(
                                        groupName,
                                        ids
                                    )
                                }
                            }.onSuccess {
                                onCreated(it)
                            }.onFailure {
                                error =
                                    it.message
                                        ?: "创建失败"
                            }
                        }
                    }
                )
            }
        )

        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
            },
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Color.White
                )
                .padding(
                    horizontal = 16.dp,
                    vertical = 8.dp
                ),
            placeholder = {
                Text(
                    "群聊名称（可不填）"
                )
            },
            singleLine = true
        )

        if (error.isNotBlank()) {
            Text(
                error,
                color = Color(
                    0xFFD93025
                ),
                modifier =
                    Modifier.padding(
                        16.dp
                    )
            )
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .background(
                    Color.White
                )
        ) {
            items(
                contacts,
                key = {
                    it.id
                }
            ) {
                contact ->
                val checked =
                    contact.id in
                        selected

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            selected =
                                if (checked) {
                                    selected -
                                        contact.id
                                } else {
                                    selected +
                                        contact.id
                                }
                        }
                        .padding(
                            horizontal = 14.dp,
                            vertical = 10.dp
                        ),
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = {
                            value ->
                            selected =
                                if (value) {
                                    selected +
                                        contact.id
                                } else {
                                    selected -
                                        contact.id
                                }
                        }
                    )

                    Spacer(
                        Modifier.width(
                            8.dp
                        )
                    )

                    Avatar(
                        contact.avatarPath,
                        contact.name
                            .take(1),
                        42
                    )

                    Spacer(
                        Modifier.width(
                            12.dp
                        )
                    )

                    Column {
                        Text(
                            contact.name,
                            fontSize =
                                16.sp
                        )

                        if (
                            contact.subtitle
                                .isNotBlank()
                        ) {
                            Text(
                                contact.subtitle,
                                fontSize =
                                    12.sp,
                                color =
                                    Color.Gray
                            )
                        }
                    }
                }

                HorizontalDivider(
                    thickness =
                        0.5.dp,
                    color =
                        DividerGray,
                    modifier =
                        Modifier.padding(
                            start =
                                72.dp
                        )
                )
            }
        }
    }
}

@Composable
fun GroupChatScreen(
    groupId: Int,
    onBack: () -> Unit,
    openInfo: () -> Unit
) {
    val context =
        LocalContext.current

    val db =
        remember {
            AppDb.get(context)
        }

    val engine =
        remember {
            GroupChatEngine(
                context
            )
        }

    val profile =
        remember {
            ProfileStore(context)
        }

    val scope =
        rememberCoroutineScope()

    val listState =
        rememberLazyListState()

    var group by remember {
        mutableStateOf<ChatGroup?>(
            null
        )
    }

    var members by remember {
        mutableStateOf(
            emptyList<Contact>()
        )
    }

    var messages by remember {
        mutableStateOf(
            emptyList<GroupMessage>()
        )
    }

    var input by remember {
        mutableStateOf("")
    }

    var speakingName by remember {
        mutableStateOf<String?>(
            null
        )
    }

    var error by remember {
        mutableStateOf("")
    }

    var activeJob by remember {
        mutableStateOf<Job?>(
            null
        )
    }

    var sessionId by remember {
        mutableLongStateOf(
            0L
        )
    }

    suspend fun loadData() {
        val result =
            withContext(
                Dispatchers.IO
            ) {
                Triple(
                    db.getGroup(
                        groupId
                    ),
                    db.getGroupMembers(
                        groupId
                    ),
                    db.getGroupMessages(
                        groupId
                    )
                )
            }

        group = result.first
        members = result.second
        messages = result.third
    }

    fun reload() {
        scope.launch {
            loadData()
        }
    }

    LaunchedEffect(groupId) {
        loadData()
    }

    LaunchedEffect(
        messages.size,
        speakingName
    ) {
        val extra =
            if (
                speakingName != null
            ) 1
            else 0

        val count =
            messages.size +
                extra

        if (count > 0) {
            runCatching {
                listState
                    .animateScrollToItem(
                        count - 1
                    )
            }
        }
    }

    val contactMap =
        remember(members) {
            members.associateBy {
                it.id
            }
        }

    Column(
        Modifier
            .fillMaxSize()
            .background(PageGray)
    ) {
        TopBar(
            title =
                group?.name
                    .orEmpty(),
            onBack =
                onBack,
            right = {
                TopIconButton(
                    onClick =
                        openInfo,
                    contentDescription =
                        "群聊信息",
                    icon =
                        Icons.Outlined.MoreHoriz
                )
            }
        )

        LazyColumn(
            state =
                listState,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            contentPadding =
                PaddingValues(
                    horizontal =
                        12.dp,
                    vertical =
                        10.dp
                ),
            verticalArrangement =
                Arrangement.spacedBy(
                    14.dp
                )
        ) {
            items(
                messages,
                key = {
                    it.id
                }
            ) {
                message ->
                GroupBubble(
                    message =
                        message,
                    contact =
                        message
                            .senderContactId
                            ?.let {
                                contactMap[it]
                            },
                    myAvatarPath =
                        profile.avatarPath
                )
            }

            speakingName?.let {
                name ->
                item(
                    key =
                        "group_typing"
                ) {
                    val c =
                        members
                            .firstOrNull {
                                it.name ==
                                    name
                            }

                    Row(
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {
                        Avatar(
                            c?.avatarPath,
                            name.take(1),
                            40
                        )

                        Spacer(
                            Modifier.width(
                                8.dp
                            )
                        )

                        Surface(
                            color =
                                Color.White,
                            shape =
                                RoundedCornerShape(
                                    5.dp
                                )
                        ) {
                            Text(
                                "$name 正在输入…",
                                color =
                                    Color.Gray,
                                fontSize =
                                    13.sp,
                                modifier =
                                    Modifier.padding(
                                        horizontal =
                                            11.dp,
                                        vertical =
                                            9.dp
                                    )
                            )
                        }
                    }
                }
            }
        }

        if (error.isNotBlank()) {
            Text(
                error,
                color =
                    Color(
                        0xFFD93025
                    ),
                fontSize =
                    12.sp,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Color.White
                        )
                        .padding(
                            horizontal =
                                12.dp,
                            vertical =
                                5.dp
                        )
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    Color(
                        0xFFF7F7F7
                    )
                )
                .padding(
                    horizontal =
                        8.dp,
                    vertical =
                        7.dp
                ),
            verticalAlignment =
                Alignment.Bottom
        ) {
            OutlinedTextField(
                value =
                    input,
                onValueChange = {
                    input = it
                },
                modifier =
                    Modifier.weight(
                        1f
                    ),
                maxLines = 5,
                colors =
                    OutlinedTextFieldDefaults
                        .colors(
                            focusedContainerColor =
                                Color.White,
                            unfocusedContainerColor =
                                Color.White,
                            focusedBorderColor =
                                Color.Transparent,
                            unfocusedBorderColor =
                                Color.Transparent
                        ),
                shape =
                    RoundedCornerShape(
                        5.dp
                    )
            )

            Spacer(
                Modifier.width(
                    6.dp
                )
            )

            Button(
                onClick = {
                    val text =
                        input.trim()

                    if (
                        text.isBlank()
                    ) {
                        return@Button
                    }

                    input = ""

                    // 群聊允许交叉消息：用户继续发送不会取消正在进行的 AI 群聊表达。
                    sessionId += 1L
                    val mySession =
                        sessionId

                    activeJob =
                        scope.launch {
                            error = ""

                            runCatching {
                                engine.sendUserText(
                                    groupId =
                                        groupId,
                                    text =
                                        text,
                                    onChanged = {
                                        reload()
                                    },
                                    onTyping = {
                                        name ->
                                        if (
                                            mySession ==
                                                sessionId
                                        ) {
                                            speakingName =
                                                name
                                        }
                                    }
                                )
                            }.onFailure {
                                if (
                                    mySession ==
                                        sessionId
                                ) {
                                    error =
                                        it.message
                                            ?: "发送失败"
                                }
                            }

                            if (
                                mySession ==
                                    sessionId
                            ) {
                                speakingName =
                                    null
                                loadData()

                                launch(
                                    Dispatchers.IO
                                ) {
                                    runCatching {
                                        engine
                                            .processPersonaEvolution(
                                                groupId
                                            )
                                    }
                                }
                            }
                        }
                },
                enabled =
                    input.isNotBlank(),
                colors =
                    ButtonDefaults
                        .buttonColors(
                            containerColor =
                                WeChatGreen
                        ),
                shape =
                    RoundedCornerShape(
                        5.dp
                    )
            ) {
                Text("发送")
            }
        }
    }
}

@Composable
fun GroupInfoScreen(
    groupId: Int,
    onBack: () -> Unit,
    onDeleted: () -> Unit
) {
    val context =
        LocalContext.current

    val db =
        remember {
            AppDb.get(context)
        }

    val scope =
        rememberCoroutineScope()

    var group by remember {
        mutableStateOf<ChatGroup?>(
            null
        )
    }

    var contacts by remember {
        mutableStateOf(
            emptyList<Contact>()
        )
    }

    var selected by remember {
        mutableStateOf(
            emptySet<Int>()
        )
    }

    var name by remember {
        mutableStateOf("")
    }

    var error by remember {
        mutableStateOf("")
    }

    LaunchedEffect(groupId) {
        val loaded =
            withContext(
                Dispatchers.IO
            ) {
                Triple(
                    db.getGroup(
                        groupId
                    ),
                    db.getContacts()
                        .sortedBy {
                            it.name
                        },
                    db.getGroupMembers(
                        groupId
                    )
                )
            }

        group =
            loaded.first

        contacts =
            loaded.second

        selected =
            loaded.third
                .map {
                    it.id
                }
                .toSet()

        name =
            loaded.first
                ?.name
                .orEmpty()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(PageGray)
    ) {
        TopBar(
            title = "群聊信息",
            onBack = onBack,
            right = {
                TopTextAction(
                    text = "保存",
                    enabled =
                        selected.size >= 2,
                    onClick = {
                        scope.launch {
                            runCatching {
                                withContext(
                                    Dispatchers.IO
                                ) {
                                    db.renameGroup(
                                        groupId,
                                        name
                                    )

                                    db.setGroupMembers(
                                        groupId,
                                        selected
                                            .toList()
                                    )
                                }
                            }.onSuccess {
                                onBack()
                            }.onFailure {
                                error =
                                    it.message
                                        ?: "保存失败"
                            }
                        }
                    }
                )
            }
        )

        WhiteField(
            "群聊名称",
            name,
            {
                name = it
            }
        )

        Spacer(
            Modifier.height(
                10.dp
            )
        )

        Text(
            "群成员",
            color =
                Color.Gray,
            fontSize =
                13.sp,
            modifier =
                Modifier.padding(
                    horizontal =
                        16.dp,
                    vertical =
                        8.dp
                )
        )

        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(
                    Color.White
                )
        ) {
            items(
                contacts,
                key = {
                    it.id
                }
            ) {
                contact ->
                val checked =
                    contact.id in
                        selected

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            selected =
                                if (checked) {
                                    selected -
                                        contact.id
                                } else {
                                    selected +
                                        contact.id
                                }
                        }
                        .padding(
                            horizontal =
                                14.dp,
                            vertical =
                                9.dp
                        ),
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked =
                            checked,
                        onCheckedChange = {
                            value ->
                            selected =
                                if (value) {
                                    selected +
                                        contact.id
                                } else {
                                    selected -
                                        contact.id
                                }
                        }
                    )

                    Spacer(
                        Modifier.width(
                            8.dp
                        )
                    )

                    Avatar(
                        contact.avatarPath,
                        contact.name
                            .take(1),
                        40
                    )

                    Spacer(
                        Modifier.width(
                            12.dp
                        )
                    )

                    Text(
                        contact.name,
                        fontSize =
                            16.sp
                    )
                }
            }
        }

        if (error.isNotBlank()) {
            Text(
                error,
                color =
                    Color(
                        0xFFD93025
                    ),
                modifier =
                    Modifier.padding(
                        16.dp
                    )
            )
        }

        WhiteActionRow(
            title = "删除群聊",
            danger = true
        ) {
            scope.launch {
                withContext(
                    Dispatchers.IO
                ) {
                    db.deleteGroup(
                        groupId
                    )
                }

                onDeleted()
            }
        }
    }
}

@Composable
private fun GroupBubble(
    message: GroupMessage,
    contact: Contact?,
    myAvatarPath: String?
) {
    val mine =
        message.senderKind ==
            "user"

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement =
            if (mine) {
                Arrangement.End
            } else {
                Arrangement.Start
            },
        verticalAlignment =
            Alignment.Top
    ) {
        if (!mine) {
            Avatar(
                contact?.avatarPath,
                message.senderName
                    .take(1)
                    .ifBlank {
                        "?"
                    },
                40
            )

            Spacer(
                Modifier.width(
                    8.dp
                )
            )
        }

        Column(
            horizontalAlignment =
                if (mine) {
                    Alignment.End
                } else {
                    Alignment.Start
                }
        ) {
            Text(
                if (mine) {
                    "我"
                } else {
                    message.senderName
                },
                fontSize =
                    11.sp,
                color =
                    Color.Gray,
                modifier =
                    Modifier.padding(
                        horizontal =
                            4.dp,
                        vertical =
                            2.dp
                    )
            )

            Surface(
                color =
                    if (mine) {
                        UserBubble
                    } else {
                        Color.White
                    },
                shape =
                    RoundedCornerShape(
                        5.dp
                    )
            ) {
                Text(
                    message.text,
                    modifier =
                        Modifier
                            .widthIn(
                                max =
                                    270.dp
                            )
                            .padding(
                                horizontal =
                                    11.dp,
                                vertical =
                                    9.dp
                            ),
                    fontSize =
                        16.sp,
                    lineHeight =
                        22.sp
                )
            }
        }

        if (mine) {
            Spacer(
                Modifier.width(
                    8.dp
                )
            )

            Avatar(
                myAvatarPath,
                "我",
                40
            )
        }
    }
}
