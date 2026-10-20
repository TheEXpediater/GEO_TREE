package com.geotree.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TreeEntity::class, LeafAssessmentEntity::class], version = 4, exportSchema = true)
abstract class GeoTreeDatabase : RoomDatabase() {
    abstract fun treeDao(): TreeDao
    abstract fun leafAssessmentDao(): LeafAssessmentDao

    companion object {
        /** v2 adds local leaf-assessment history. The trees table is untouched. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `leaf_assessments` (`id` TEXT NOT NULL, `treeId` TEXT NOT NULL, " +
                        "`imageLocalPath` TEXT NOT NULL, `status` TEXT NOT NULL, `classId` TEXT, `className` TEXT, " +
                        "`confidence` REAL, `uncertain` INTEGER, `model` TEXT, `modelDisplayName` TEXT, `modelVersion` INTEGER, " +
                        "`errorCode` TEXT, `errorMessage` TEXT, `createdAt` INTEGER NOT NULL, `assessedAt` INTEGER, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_leaf_assessments_treeId_createdAt` ON `leaf_assessments` (`treeId`, `createdAt`)",
                )
            }
        }

        /**
         * v3 turns leaf assessments into synced history: AI status (COMPLETED / UNCERTAIN / FAILED…)
         * separate from sync status, completion/update times, server version and remote photo.
         * SQLite cannot drop or retype columns, so the table is rebuilt and every row is copied:
         *  COMPLETE + uncertain → UNCERTAIN, COMPLETE → COMPLETED, FAILED → FAILED,
         *  PENDING (a request that never finished) → FAILED / INTERRUPTED. No row is dropped.
         * Every migrated row starts as sync PENDING. The trees table is untouched.
         */
        val MIGRATION_2_3_SQL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `leaf_assessments_new` (`id` TEXT NOT NULL, `treeId` TEXT NOT NULL, " +
                "`imageLocalPath` TEXT, `remoteImagePath` TEXT, `classId` TEXT, `className` TEXT, `confidence` REAL, " +
                "`modelName` TEXT, `modelDisplayName` TEXT, `modelVersion` INTEGER, `status` TEXT NOT NULL, " +
                "`errorCode` TEXT, `errorMessage` TEXT, `createdAt` INTEGER NOT NULL, `completedAt` INTEGER, " +
                "`updatedAt` INTEGER NOT NULL, `syncStatus` TEXT NOT NULL, `serverVersion` INTEGER, `lastSyncError` TEXT, " +
                "PRIMARY KEY(`id`))",
            "INSERT INTO `leaf_assessments_new` (`id`, `treeId`, `imageLocalPath`, `remoteImagePath`, `classId`, `className`, " +
                "`confidence`, `modelName`, `modelDisplayName`, `modelVersion`, `status`, `errorCode`, `errorMessage`, " +
                "`createdAt`, `completedAt`, `updatedAt`, `syncStatus`, `serverVersion`, `lastSyncError`) " +
                "SELECT `id`, `treeId`, `imageLocalPath`, NULL, `classId`, `className`, `confidence`, `model`, " +
                "`modelDisplayName`, `modelVersion`, " +
                "CASE WHEN `status` = 'COMPLETE' AND `uncertain` = 1 THEN 'UNCERTAIN' " +
                "WHEN `status` = 'COMPLETE' THEN 'COMPLETED' ELSE 'FAILED' END, " +
                "CASE WHEN `status` = 'PENDING' THEN 'INTERRUPTED' ELSE `errorCode` END, " +
                "CASE WHEN `status` = 'PENDING' THEN 'The assessment was interrupted before a result arrived. The photo is kept.' " +
                "ELSE `errorMessage` END, " +
                "`createdAt`, `assessedAt`, COALESCE(`assessedAt`, `createdAt`), 'PENDING', NULL, NULL " +
                "FROM `leaf_assessments`",
            "DROP TABLE `leaf_assessments`",
            "ALTER TABLE `leaf_assessments_new` RENAME TO `leaf_assessments`",
            "CREATE INDEX IF NOT EXISTS `index_leaf_assessments_treeId_createdAt` ON `leaf_assessments` (`treeId`, `createdAt`)",
            "CREATE INDEX IF NOT EXISTS `index_leaf_assessments_status` ON `leaf_assessments` (`status`)",
            "CREATE INDEX IF NOT EXISTS `index_leaf_assessments_syncStatus` ON `leaf_assessments` (`syncStatus`)",
        )

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) = MIGRATION_2_3_SQL.forEach(db::execSQL)
        }

        /** v4 adds the web administration's soft delete. Existing trees stay active (0). */
        val MIGRATION_3_4_SQL: List<String> = listOf(
            "ALTER TABLE `trees` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0",
        )

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) = MIGRATION_3_4_SQL.forEach(db::execSQL)
        }

        fun build(context: Context): GeoTreeDatabase =
            Room.databaseBuilder(context, GeoTreeDatabase::class.java, "geo_tree.db")
                // Schema changes must ship explicit Migration objects (schemas/ is exported).
                // Never fall back to destructive migration: field records must not be wiped.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
