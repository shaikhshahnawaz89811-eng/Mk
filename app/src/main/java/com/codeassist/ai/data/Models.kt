package com.codeassist.ai.data

import java.util.UUID

enum class AttachKind { IMAGE, ZIP, FILE }

data class Attachment(
    val uri: String,
    val name: String,
    val size: Long,
    val mime: String,
    val kind: AttachKind
)

enum class Role { USER, AI }

/** TEXT = normal bubble; ACTIVITY = live run timeline; APPROVAL = inline interruption card. */
enum class MsgKind { TEXT, ACTIVITY, APPROVAL }

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val fileName: String? = null,
    val fileType: String? = null,
    val time: Long = System.currentTimeMillis(),
    val kind: MsgKind = MsgKind.TEXT,
    val runId: String? = null,          // ACTIVITY/APPROVAL messages bind to a run
    val branchOf: String? = null        // edited messages keep the old branch id
)

data class ChatMeta(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    var snippet: String = "",
    val projectId: String? = null,
    var time: Long = System.currentTimeMillis()
)

data class Project(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var desc: String = "",
    var iconIdx: Int = 0,
    var colorIdx: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    var chats: Int = 0,
    var files: MutableList<String> = mutableListOf(),
    var instructions: MutableList<String> = mutableListOf()
)
