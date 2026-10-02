package com.codeassist.ai.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object Store {

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) {
            prefs = ctx.applicationContext.getSharedPreferences("codeassist", Context.MODE_PRIVATE)
        }
    }

    // ---------- Settings ----------
    var glassEffect: Boolean
        get() = prefs.getBoolean("glass", true)
        set(v) = prefs.edit().putBoolean("glass", v).apply()

    var pushNotifications: Boolean
        get() = prefs.getBoolean("push", true)
        set(v) = prefs.edit().putBoolean("push", v).apply()

    var sound: Boolean
        get() = prefs.getBoolean("sound", true)
        set(v) = prefs.edit().putBoolean("sound", v).apply()

    var autoSave: Boolean
        get() = prefs.getBoolean("autosave", true)
        set(v) = prefs.edit().putBoolean("autosave", v).apply()

    var openLastProject: Boolean
        get() = prefs.getBoolean("openlast", false)
        set(v) = prefs.edit().putBoolean("openlast", v).apply()

    var model: String
        get() = prefs.getString("model", "GPT-4o") ?: "GPT-4o"
        set(v) = prefs.edit().putString("model", v).apply()

    var effort: String
        get() = prefs.getString("effort", "Balanced") ?: "Balanced"
        set(v) = prefs.edit().putString("effort", v).apply()

    var textSize: String
        get() = prefs.getString("textsize", "Medium") ?: "Medium"
        set(v) = prefs.edit().putString("textsize", v).apply()

    var dataUsage: String
        get() = prefs.getString("datausage", "Keep in app") ?: "Keep in app"
        set(v) = prefs.edit().putString("datausage", v).apply()

    var lastProjectId: String?
        get() = prefs.getString("lastproject", null)
        set(v) = prefs.edit().putString("lastproject", v).apply()

    /** Permission mode — Spec §13: Ask when needed / Auto / Skip approvals. */
    var permissionMode: String
        get() = prefs.getString("permmode", "Ask when needed") ?: "Ask when needed"
        set(v) = prefs.edit().putString("permmode", v).apply()

    /** Chat currently open on the home screen; restored after background / process death. */
    var activeChatId: String?
        get() = prefs.getString("activechat", null)
        set(v) = prefs.edit().putString("activechat", v).apply()

    // ---------- Projects ----------
    fun projects(): MutableList<Project> {
        val json = prefs.getString("projects", null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<Project>>() {}.type
        return try { gson.fromJson(json, type) } catch (e: Exception) { mutableListOf() }
    }

    fun saveProjects(list: List<Project>) {
        prefs.edit().putString("projects", gson.toJson(list)).apply()
    }

    fun addProject(p: Project) {
        val list = projects()
        list.add(0, p)
        saveProjects(list)
        lastProjectId = p.id
    }

    fun updateProject(p: Project) {
        val list = projects()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) { list[i] = p; saveProjects(list) }
    }

    fun deleteProject(id: String) {
        saveProjects(projects().filterNot { it.id == id })
    }

    fun project(id: String?): Project? = projects().firstOrNull { it.id == id }

    // ---------- Chats ----------
    fun chats(): MutableList<ChatMeta> {
        val json = prefs.getString("chats", null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<ChatMeta>>() {}.type
        return try { gson.fromJson(json, type) } catch (e: Exception) { mutableListOf() }
    }

    fun saveChats(list: List<ChatMeta>) {
        prefs.edit().putString("chats", gson.toJson(list)).apply()
    }

    fun newChat(title: String, projectId: String? = null): ChatMeta {
        val chat = ChatMeta(title = title, projectId = projectId)
        val list = chats()
        list.add(0, chat)
        saveChats(list)
        if (projectId != null) {
            project(projectId)?.let { it.chats += 1; updateProject(it) }
        }
        return chat
    }

    fun updateChat(c: ChatMeta) {
        val list = chats()
        val i = list.indexOfFirst { it.id == c.id }
        if (i >= 0) { list[i] = c; saveChats(list) }
    }

    fun deleteChat(id: String) {
        saveChats(chats().filterNot { it.id == id })
        prefs.edit().remove("msgs_$id").apply()
        if (activeChatId == id) activeChatId = null
    }

    fun clearHistory() {
        saveChats(mutableListOf())
        prefs.all.keys.filter { it.startsWith("msgs_") }
            .forEach { prefs.edit().remove(it).apply() }
        activeChatId = null
    }

    // ---------- Messages ----------
    fun messages(chatId: String): MutableList<Message> {
        val json = prefs.getString("msgs_$chatId", null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<Message>>() {}.type
        return try { gson.fromJson(json, type) } catch (e: Exception) { mutableListOf() }
    }

    fun saveMessages(chatId: String, list: List<Message>) {
        prefs.edit().putString("msgs_$chatId", gson.toJson(list)).apply()
    }
}
