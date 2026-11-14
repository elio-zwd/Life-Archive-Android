package com.elio.jianyu.backup.complete

import android.database.sqlite.SQLiteDatabase
import android.content.Context
import android.database.Cursor
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject
import java.security.MessageDigest
import java.nio.ByteBuffer

internal object CompleteDatabase {
    @Volatile private var expectedDefinitions: Map<String, String>? = null
    fun initialize(context: Context) = initializeSchema(context.assets.open("jianyu-transfer/schema-v18.json").bufferedReader().use { it.readText() })
    fun initializeSchema(raw: String) {
        val schema = JSONObject(raw).getJSONObject("database")
        require(schema.getString("identityHash") == CompleteArchiveCodec.SCHEMA && schema.getInt("version") == 18)
        val expected = linkedMapOf<String, String>()
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val table = entity.getString("tableName")
            expected[table] = normalizeSql(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indexes = entity.optJSONArray("indices") ?: continue
            for (entry in 0 until indexes.length()) {
                val item = indexes.getJSONObject(entry)
                expected[item.getString("name")] = normalizeSql(item.getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        expectedDefinitions = expected
    }
    const val AUDIO_TOKEN = "jyfull:content/"
    val tables = setOf(
        "characters", "chat_sessions", "conversation_summary_checkpoints", "messages", "character_groups",
        "issues", "stages", "execution_runs", "execution_participant_snapshots", "execution_participant_states", "execution_run_budgets",
        "material_references", "material_usage_snapshots", "personal_context_entries", "personal_context_usage_snapshots", "skill_knowledge_usage_snapshots",
        "stage_summary_drafts", "stage_summary_draft_revisions", "confirmed_artifacts", "artifact_message_sources", "artifact_run_sources", "artifact_draft_sources", "artifact_material_sources",
        "audio_assets", "official_skill_combinations", "official_skill_combination_members", "issue_lifecycle", "issue_archive_events", "issue_resume_events", "issue_relations", "issue_purge_operations",
        "cross_discussion_sessions", "execution_message_usage_snapshots", "stage_advancements", "stage_advancement_measures", "stage_advancement_skill_members", "stage_advancement_materials", "stage_advancement_artifacts",
    )

    fun inspect(file: File, expected: Map<String, Long>? = null): Map<String, Long> = open(file, false).use { database ->
        require(database.version == 18) { "目前只支持见域 Room v18 完整格式" }
        require(database.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { it.moveToFirst() && it.getString(0) == CompleteArchiveCodec.SCHEMA }) { "见域数据库 Schema 不匹配" }
        require(database.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" && !it.moveToNext() }) { "见域数据库完整性检查失败" }
        require(database.rawQuery("PRAGMA foreign_key_check", null).use { !it.moveToFirst() }) { "见域数据库存在断裂关联" }
        val actual = database.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } - setOf("android_metadata", "room_master_table")
        }
        require(actual == tables) { "见域数据库包含未支持或缺少的业务表" }
        val expectedSql = checkNotNull(expectedDefinitions) { "完整备份结构清单尚未装配" }
        val actualSql = linkedMapOf<String, String>()
        database.rawQuery("SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','view','trigger')", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                if (name.startsWith("sqlite_") || name in setOf("android_metadata", "room_master_table")) continue
                require(cursor.getString(0) in setOf("table", "index")) { "完整格式不支持额外的视图或触发器" }
                actualSql[name] = normalizeSql(cursor.getString(2))
            }
        }
        require(actualSql == expectedSql) { "见域数据库的实际表／索引结构与 v18 不一致" }
        val rows = tables.sorted().associateWith { table -> count(database, "SELECT COUNT(*) FROM `$table`") }
        require(expected == null || rows == expected) { "见域记录数量与清单不一致" }
        rows
    }

    /** 即使后台任务已释放，也拒绝把尚未确认完成的工作当成完整数据导出。 */
    fun requireIdle(file: File) = open(file, false).use { database ->
        require(count(database, "SELECT COUNT(*) FROM messages WHERE isPending=1") == 0L) { "请先停止正在生成的回答" }
        require(count(database, "SELECT COUNT(*) FROM audio_assets WHERE fileState='pending'") == 0L) { "请先完成或停止音频生成" }
        require(count(database, "SELECT COUNT(*) FROM execution_runs WHERE status='running'") == 0L) { "请先停止执行任务" }
        require(count(database, "SELECT COUNT(*) FROM issue_purge_operations WHERE state NOT IN ('completed','succeeded','purged','canceled')") == 0L) { "清除操作尚未结束，不能完整导出或恢复" }
    }

    /** staging 已是独立副本，绝不改动源 DB；所有外部绝对音频路径在这里转为受控包内引用。 */
    fun prepareExport(directory: File, resolveAudio: (String) -> File?): List<String> {
        val databaseFile = File(directory, "database.sqlite")
        val notes = mutableListOf<String>()
        open(databaseFile, true).use { database ->
            database.beginTransaction()
            try {
                database.rawQuery("SELECT id,audioFilePath FROM messages WHERE audioFilePath IS NOT NULL AND audioFilePath<>''", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0); val path = cursor.getString(1)
                        val audio = resolveAudio(path) ?: error("消息 $id 的音频缺失或不属于见域，完整导出已停止")
                        require(audio.isFile && audio.length() > 0) { "消息 $id 的音频缺失或为空" }
                        val hash = CompleteArchiveCodec.sha256(audio)
                        val extension = audio.extension.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
                        val relative = "content/jianyu-audio/legacy/$hash.$extension"
                        val target = CompleteArchiveCodec.ownedFile(directory, relative)
                        target.parentFile!!.mkdirs()
                        if (!target.exists()) copySynced(audio, target)
                        database.execSQL("UPDATE messages SET audioFilePath=? WHERE id=?", arrayOf<Any>(AUDIO_TOKEN + relative.removePrefix("content/"), id))
                    }
                }
                database.rawQuery("SELECT id,fileState FROM audio_assets WHERE fileState<>'available'", null).use { cursor ->
                    while (cursor.moveToNext()) notes += "音频记录 ${cursor.getString(0)} 原状态为 ${cursor.getString(1)}；记录保留，不承诺补回原不存在的音频。"
                }
                val external = count(database, "SELECT COUNT(*) FROM material_references WHERE sourceLocator LIKE 'content://%'")
                if (external > 0) notes += "$external 份资料的原文件 URI 授权不能迁移；已经提取的正文、来源说明和快照保留。"
                require(count(database, "SELECT COUNT(*) FROM material_references WHERE sourceLocator LIKE 'content://%' AND content='' AND lifecycleState<>'purged'") == 0L) { "存在只有外部 URI、没有可迁移正文的资料，完整导出已停止" }
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
            // 只压实独立暂存库，避免已彻底删除的内容随 SQLite 空闲页进入备份；
            // 显式主键与 sqlite_sequence 随 VACUUM 保留，原库不参与这一步。
            database.execSQL("VACUUM")
        }
        verifyFiles(directory, false)
        return notes
    }

    fun verifyFiles(directory: File, localAudioPaths: Boolean, contentRoot: File? = null) = open(File(directory, "database.sqlite"), false).use { database ->
        database.rawQuery("SELECT id,audioFilePath FROM messages WHERE audioFilePath IS NOT NULL AND audioFilePath<>''", null).use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(1)
                val relative = if (localAudioPaths) {
                    val root = requireNotNull(contentRoot).canonicalFile
                    val file = File(path).canonicalFile
                    require(file.toPath().startsWith(root.toPath())) { "恢复后的音频路径越界" }
                    file.relativeTo(root).invariantSeparatorsPath
                } else {
                    require(path.startsWith(AUDIO_TOKEN)) { "完整格式包含未转换的绝对音频路径" }
                    path.removePrefix(AUDIO_TOKEN)
                }
                val file = CompleteArchiveCodec.ownedFile(directory, "content/$relative")
                require(file.isFile && file.length() > 0) { "消息 ${cursor.getLong(0)} 缺少音频" }
            }
        }
        database.rawQuery("SELECT id,storagePath,sizeBytes FROM audio_assets WHERE fileState='available'", null).use { cursor ->
            while (cursor.moveToNext()) {
                val file = CompleteArchiveCodec.ownedFile(directory, "content/jianyu-audio/${cursor.getString(1)}")
                require(file.isFile && file.length() == cursor.getLong(2)) { "音频 ${cursor.getString(0)} 缺失或大小不符" }
            }
        }
    }

    fun prepareImport(directory: File, targetContent: File) {
        verifyFiles(directory, false)
        open(File(directory, "database.sqlite"), true).use { database ->
            database.beginTransaction()
            try {
                database.rawQuery("SELECT id,audioFilePath FROM messages WHERE audioFilePath IS NOT NULL AND audioFilePath<>''", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val relative = cursor.getString(1).removePrefix(AUDIO_TOKEN)
                        // 同一套路径校验用于包内和目标根，目标文件到提交时才出现。
                        val staged = CompleteArchiveCodec.ownedFile(directory, "content/$relative")
                        check(staged.isFile)
                        val target = File(targetContent, relative).canonicalFile
                        require(target.toPath().startsWith(targetContent.canonicalFile.toPath()))
                        database.execSQL("UPDATE messages SET audioFilePath=? WHERE id=?", arrayOf<Any>(target.absolutePath, cursor.getLong(0)))
                    }
                }
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
        }
    }

    fun isEmpty(file: File): Boolean = open(file, false).use { database ->
        val userTables = tables - setOf("characters", "character_groups")
        userTables.all { count(database, "SELECT COUNT(*) FROM `$it`") == 0L } &&
            count(database, "SELECT COUNT(*) FROM characters WHERE systemPrompt<>''") == 0L &&
            count(database, "SELECT COUNT(*) FROM character_groups WHERE isPreset=0") == 0L
    }

    /** 预览与提交之间核对当前内容，避免用户备份后继续产生的数据被无声覆盖。 */
    fun fingerprint(file: File, settings: String, content: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun bytes(value: ByteArray) { digest.update(ByteBuffer.allocate(8).putLong(value.size.toLong()).array()); digest.update(value) }
        fun text(value: String) = bytes(value.toByteArray(Charsets.UTF_8))
        // Android 旧版本会把 BEGIN 转成写事务入口，不能在 OPEN_READONLY 连接发起。
        // 这里只读已存在的 WAL 库并回滚空事务，不执行任何业务数据写语句。
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { database ->
            database.beginTransactionNonExclusive()
            try {
                for (table in tables.sorted()) {
                    text(table)
                    val columns = database.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(1)) } }
                    val order = columns.joinToString(",") { "`$it`" }
                    database.rawQuery("SELECT * FROM `$table` ORDER BY $order", null).use { cursor ->
                        while (cursor.moveToNext()) for (index in 0 until cursor.columnCount) {
                            val type = cursor.getType(index); digest.update(type.toByte())
                            when (type) {
                                Cursor.FIELD_TYPE_INTEGER -> bytes(ByteBuffer.allocate(8).putLong(cursor.getLong(index)).array())
                                Cursor.FIELD_TYPE_FLOAT -> bytes(ByteBuffer.allocate(8).putDouble(cursor.getDouble(index)).array())
                                Cursor.FIELD_TYPE_STRING -> text(cursor.getString(index))
                                Cursor.FIELD_TYPE_BLOB -> bytes(cursor.getBlob(index))
                            }
                        }
                    }
                }
            } finally { database.endTransaction() }
        }
        text(settings)
        if (content.exists()) for (owned in content.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(content).invariantSeparatorsPath }) {
            require(owned.canonicalFile.toPath().startsWith(content.canonicalFile.toPath()) && !java.nio.file.Files.isSymbolicLink(owned.toPath()))
            text(owned.relativeTo(content).invariantSeparatorsPath); text(CompleteArchiveCodec.sha256(owned))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun open(file: File, writable: Boolean): SQLiteDatabase {
        require(file.isFile && file.length() > 0) { "缺少见域数据库" }
        return SQLiteDatabase.openDatabase(file.absolutePath, null, (if (writable) SQLiteDatabase.OPEN_READWRITE else SQLiteDatabase.OPEN_READONLY) or SQLiteDatabase.NO_LOCALIZED_COLLATORS).also { database ->
            if (writable) database.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor -> check(cursor.moveToFirst() && cursor.getString(0).equals("delete", true)) }
        }
    }

    private fun count(database: SQLiteDatabase, sql: String): Long = database.rawQuery(sql, null).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
    private fun normalizeSql(value: String) = value.replace(Regex("(?i)IF\\s+NOT\\s+EXISTS\\s+"), "").trim()

    fun copySynced(source: File, target: File) {
        require(source.canonicalFile != target.canonicalFile)
        target.parentFile!!.mkdirs()
        source.inputStream().use { input -> FileOutputStream(target).use { output -> input.copyTo(output, 64 * 1024); output.fd.sync() } }
    }
}
