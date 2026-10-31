package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AppSettingsEntity::class,
        MarketCandleEntity::class,
        MarketFeatureEntity::class,
        SignalEntity::class,
        AiDecisionEntity::class,
        AiRequestEntity::class,
        OrderIntentEntity::class,
        OrderRecordEntity::class,
        FillEntity::class,
        PositionEntity::class,
        RiskEventEntity::class,
        SystemEventEntity::class,
        DailySnapshotEntity::class,
        CashflowEntity::class,
        FundingLedgerEntity::class,
        ProviderConfigEntity::class,
        TelegramUpdateEntity::class,
        ForecastModelEntity::class,
        ForecastPredictionEntity::class,
        ForecastOutcomeEntity::class,
        ForecastMetricEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AstraDatabase : RoomDatabase() {
    abstract fun astraDao(): AstraDao

    fun runIntegrityCheck(): Boolean {
        return try {
            val db = openHelper.writableDatabase
            db.query("PRAGMA integrity_check").use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0).equals("ok", ignoreCase = true)
                } else {
                    false
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AstraDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7
        )

        fun getInstance(context: Context): AstraDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AstraDatabase::class.java,
                    "astra_trader_v51.db"
                )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(*ALL_MIGRATIONS)
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
