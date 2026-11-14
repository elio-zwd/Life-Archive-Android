package com.elio.jianyu.data

import com.elio.jianyu.domain.*

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.elio.jianyu.telemetry.PrivacySafeLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope

@Database(
    entities = [
        com.elio.jianyu.data.persistence.Character::class,
        com.elio.jianyu.data.persistence.ChatSession::class,
        com.elio.jianyu.data.persistence.ConversationSummaryCheckpoint::class,
        com.elio.jianyu.data.persistence.Message::class,
        com.elio.jianyu.data.persistence.CharacterGroup::class,
        com.elio.jianyu.data.persistence.IssueEntity::class,
        com.elio.jianyu.data.persistence.StageEntity::class,
        com.elio.jianyu.data.persistence.ExecutionRunEntity::class,
        com.elio.jianyu.data.persistence.ExecutionParticipantSnapshotEntity::class,
        com.elio.jianyu.data.persistence.ExecutionParticipantStateEntity::class,
        com.elio.jianyu.data.persistence.ExecutionRunBudgetEntity::class,
        com.elio.jianyu.data.persistence.MaterialReferenceEntity::class,
        com.elio.jianyu.data.persistence.MaterialUsageSnapshotEntity::class,
        com.elio.jianyu.data.persistence.PersonalContextEntryEntity::class,
        com.elio.jianyu.data.persistence.PersonalContextUsageSnapshotEntity::class,
        com.elio.jianyu.data.persistence.SkillKnowledgeUsageSnapshotEntity::class,
        com.elio.jianyu.data.persistence.StageSummaryDraftEntity::class,
        com.elio.jianyu.data.persistence.StageSummaryDraftRevisionEntity::class,
        com.elio.jianyu.data.persistence.ConfirmedArtifactEntity::class,
        com.elio.jianyu.data.persistence.ArtifactMessageSourceEntity::class,
        com.elio.jianyu.data.persistence.ArtifactRunSourceEntity::class,
        com.elio.jianyu.data.persistence.ArtifactDraftSourceEntity::class,
        com.elio.jianyu.data.persistence.ArtifactMaterialSourceEntity::class,
        com.elio.jianyu.data.persistence.AudioAssetEntity::class,
        com.elio.jianyu.data.persistence.OfficialSkillCombinationEntity::class,
        com.elio.jianyu.data.persistence.OfficialSkillCombinationMemberEntity::class,
        com.elio.jianyu.data.persistence.IssueLifecycleEntity::class,
        com.elio.jianyu.data.persistence.IssueArchiveEventEntity::class,
        com.elio.jianyu.data.persistence.IssueResumeEventEntity::class,
        com.elio.jianyu.data.persistence.IssueRelationEntity::class,
        com.elio.jianyu.data.persistence.IssuePurgeOperationEntity::class,
        com.elio.jianyu.data.persistence.CrossDiscussionSessionEntity::class,
        com.elio.jianyu.data.persistence.ExecutionMessageUsageSnapshotEntity::class,
        com.elio.jianyu.data.persistence.StageAdvancementEntity::class,
        com.elio.jianyu.data.persistence.StageAdvancementMeasureEntity::class,
        com.elio.jianyu.data.persistence.StageAdvancementSkillMemberEntity::class,
        com.elio.jianyu.data.persistence.StageAdvancementMaterialEntity::class,
        com.elio.jianyu.data.persistence.StageAdvancementArtifactEntity::class,
    ],
    version = 18,
    exportSchema = true,
)
@TypeConverters(
    CoreDomainConverters::class,
    ResourceLifecycleConverters::class,
    ExecutionRuntimeConverters::class,
    CollaborationConverters::class,
    StageAdvancementConverters::class,
    IssueLifecycleV12Converters::class,
)
abstract class RoundtableDatabase : RoomDatabase() {
    private val explicitlyClosed = AtomicBoolean(false)

    internal val isExplicitlyClosed: Boolean
        get() = explicitlyClosed.get()

    final override fun close() {
        explicitlyClosed.set(true)
        super.close()
    }

    abstract fun characterDao(): CharacterDao
    abstract fun chatDao(): ChatDao
    abstract fun characterGroupDao(): CharacterGroupDao
    abstract fun coreDomainDao(): CoreDomainDao
    abstract fun resourceLifecycleDao(): ResourceLifecycleDao
    internal abstract fun jianyuRepositoryDao(): JianyuRepositoryDao
    internal abstract fun collaborationDao(): CollaborationDao
    internal abstract fun stageAdvancementDao(): StageAdvancementDao
    internal abstract fun issueLifecycleV12Dao(): IssueLifecycleV12Dao

    companion object {
        @Volatile
        private var INSTANCE: RoundtableDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!LegacyDatabaseMigrationSupport.columnExists(
                        db,
                        "characters",
                        "skillAssetPath",
                    )
                ) {
                    db.execSQL(
                        "ALTER TABLE characters " +
                            "ADD COLUMN skillAssetPath TEXT NOT NULL DEFAULT ''",
                    )
                }
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!LegacyDatabaseMigrationSupport.columnExists(
                        db,
                        "characters",
                        "skillDescriptionVector",
                    )
                ) {
                    db.execSQL(
                        "ALTER TABLE characters " +
                            "ADD COLUMN skillDescriptionVector TEXT NOT NULL DEFAULT ''",
                    )
                }
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!LegacyDatabaseMigrationSupport.columnExists(db, "messages", "roundIndex")) {
                    db.execSQL(
                        "ALTER TABLE messages " +
                            "ADD COLUMN roundIndex INTEGER NOT NULL DEFAULT 0",
                    )
                }
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!LegacyDatabaseMigrationSupport.columnExists(db, "messages", "roundIndex")) {
                    db.execSQL(
                        "ALTER TABLE messages " +
                            "ADD COLUMN roundIndex INTEGER NOT NULL DEFAULT 0",
                    )
                }
                if (!LegacyDatabaseMigrationSupport.columnExists(db, "messages", "audioFilePath")) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN audioFilePath TEXT")
                }
                if (!LegacyDatabaseMigrationSupport.columnExists(db, "messages", "audioFormat")) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN audioFormat TEXT")
                }
                if (!LegacyDatabaseMigrationSupport.columnExists(db, "messages", "audioSizeBytes")) {
                    db.execSQL(
                        "ALTER TABLE messages " +
                            "ADD COLUMN audioSizeBytes INTEGER NOT NULL DEFAULT 0",
                    )
                }
                LegacyDatabaseMigrationSupport.rebuildCharactersForVersion5(db)
                LegacyDatabaseMigrationSupport.createCharacterGroupsTable(db)
                LegacyDatabaseMigrationSupport.seedPresetGroups(db)
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                LegacyDatabaseMigrationSupport.createCoreDomainTablesForVersion6(db)
                LegacyDatabaseMigrationSupport.backfillLegacySessionsForVersion6(db)
                LegacyDatabaseMigrationSupport.rebuildMessagesForVersion6(db)
            }
        }

        val MIGRATION_6_7: Migration = ResourceLifecycleMigration.MIGRATION_6_7
        val MIGRATION_7_8: Migration = ExecutionRuntimeMigration.MIGRATION_7_8
        val MIGRATION_8_9: Migration = MaterialContextMigration.MIGRATION_8_9
        val MIGRATION_9_10: Migration = CollaborationMigration.MIGRATION_9_10
        val MIGRATION_10_11: Migration = StageAdvancementMigration.MIGRATION_10_11
        val MIGRATION_11_12: Migration = IssueLifecycleV12Migration.MIGRATION_11_12
        val MIGRATION_12_13: Migration = ExecutionThinkingPolicyMigration.MIGRATION_12_13
        val MIGRATION_13_14: Migration = ExecutionApiUsageMigration.MIGRATION_13_14
        val MIGRATION_14_15: Migration = SkillKnowledgeContextMigration.MIGRATION_14_15
        val MIGRATION_15_16: Migration = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `messages` ADD COLUMN `questionMessageId` INTEGER DEFAULT NULL",
                )
                database.execSQL(
                    "ALTER TABLE `messages` ADD COLUMN `responseMode` TEXT NOT NULL DEFAULT 'INDEPENDENT'",
                )
                database.execSQL(
                    "ALTER TABLE `messages` ADD COLUMN `answerStatus` TEXT NOT NULL DEFAULT 'COMPLETED'",
                )
            }
        }

        val MIGRATION_16_17: Migration = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `messages` ADD COLUMN `conversationContextSnapshotIds` TEXT DEFAULT NULL",
                )
            }
        }

        val MIGRATION_17_18: Migration = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `conversation_summary_checkpoints` (" +
                        "`chatId` INTEGER NOT NULL, `characterId` TEXT NOT NULL, `summary` TEXT NOT NULL, " +
                        "`coveredThroughMessageId` INTEGER NOT NULL, `sourceFingerprint` TEXT NOT NULL, " +
                        "`promptVersion` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`chatId`, `characterId`), " +
                        "FOREIGN KEY(`chatId`) REFERENCES `chat_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
        )

        fun getDatabase(context: Context, scope: CoroutineScope): RoundtableDatabase {
            val current = INSTANCE
            if (current != null && !current.isExplicitlyClosed) {
                return current
            }
            return synchronized(this) {
                val synchronizedCurrent = INSTANCE
                if (synchronizedCurrent != null && !synchronizedCurrent.isExplicitlyClosed) {
                    synchronizedCurrent
                } else {
                    if (synchronizedCurrent?.isExplicitlyClosed == true) {
                        INSTANCE = null
                    }
                    buildDatabase(context, scope).also { instance ->
                        INSTANCE = instance
                    }
                }
            }
        }

        /**
         * 仅允许生命周期所有者关闭它持有的当前单例。
         *
         * 先在同一 companion 锁内摘除 [INSTANCE]，再关闭旧实例；并发 `getDatabase` 会等待关闭
         * 完成后创建新实例，不能拿到半关闭对象。
         */
        internal fun closeAndClear(expected: RoundtableDatabase) {
            synchronized(this) {
                check(INSTANCE === expected) {
                    "只能关闭当前 RoundtableDatabase 单例"
                }
                INSTANCE = null
                expected.close()
            }
        }

        private fun buildDatabase(
            context: Context,
            @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
        ): RoundtableDatabase = Room.databaseBuilder(
            context.applicationContext,
            RoundtableDatabase::class.java,
            "jianyu_roundtable_database",
        )
            .addMigrations(*ALL_MIGRATIONS)
            .addCallback(DatabaseCallback(context.applicationContext))
            .build()
    }

    private class DatabaseCallback(
        private val context: Context,
    ) : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            // 建库回调必须在数据库创建生命周期内完成。异步启动会让运行时关闭旧实例后，
            // 后台任务继续访问已关闭的 SupportSQLiteDatabase，导致重开数据库竞态崩溃。
            val configs = com.elio.jianyu.skill.SkillLoader.loadSkillsConfig(context)
            db.beginTransaction()
            try {
                configs.forEach { config ->
                    db.execSQL(
                        "INSERT OR REPLACE INTO characters " +
                            "(id, name, avatar, tagline, systemPrompt, skillAssetPath, `order`, " +
                            "isActive, skillDescriptionVector, voiceConfig) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf<Any?>(
                            config.id,
                            config.name,
                            config.avatar,
                            config.tagline,
                            "",
                            config.skillAssetPath,
                            config.order,
                            if (config.isActive) 1 else 0,
                            config.descriptionVector.joinToString(","),
                            config.voiceConfig,
                        ),
                    )
                }
                LegacyDatabaseMigrationSupport.seedPresetGroups(db)
                db.setTransactionSuccessful()
                PrivacySafeLogger.d("RoundtableDatabase", "Database seed completed")
            } catch (error: Exception) {
                PrivacySafeLogger.e("RoundtableDatabase", "Database seed failed", error)
            } finally {
                db.endTransaction()
            }
        }
    }
}
