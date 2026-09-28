package com.geotree.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TreeEntity::class], version = 1, exportSchema = true)
abstract class GeoTreeDatabase : RoomDatabase() {
    abstract fun treeDao(): TreeDao

    companion object {
        fun build(context: Context): GeoTreeDatabase =
            Room.databaseBuilder(context, GeoTreeDatabase::class.java, "geo_tree.db")
                // Schema changes must ship explicit Migration objects (schemas/ is exported).
                // Never fall back to destructive migration: field records must not be wiped.
                .build()
    }
}
