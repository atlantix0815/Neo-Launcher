/*
 * This file is part of Neo Launcher
 * Copyright (c) 2022   Neo Launcher Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.saggitt.omega.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.android.launcher3.util.MainThreadInitializedObject
import com.saggitt.omega.data.models.AppTracker
import com.saggitt.omega.data.models.GestureItemInfo
import com.saggitt.omega.data.models.IconOverride
import com.saggitt.omega.data.models.PeopleInfo

@Database(
    entities = [
        IconOverride::class,
        AppTracker::class,
        PeopleInfo::class,
        GestureItemInfo::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NeoLauncherDb : RoomDatabase() {

    abstract fun iconOverrideDao(): IconOverrideDao
    abstract fun appTrackerDao(): AppTrackerDao
    abstract fun peopleDao(): PeopleDao
    abstract fun gestureItemInfoDao(): GestureItemInfoDao

    companion object {

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE AppTracker (packageName TEXT not Null, count INTEGER not Null, PRIMARY KEY(packageName))")
            }
        }

        /*
        * Add Migration for Contacts
        */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE PeopleInfo (contactId TEXT NOT NULL, contactName TEXT not Null, contactPhone TEXT not Null, PRIMARY KEY(contactId))")
            }
        }

        /*
        * Add Migration for SwipeUp action
        */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE GestureItemInfo (" +
                            "packageName TEXT not Null, " +
                            "swipeUp TEXT NULL DEFAULT '', " +
                            "swipeDown TEXT NULL DEFAULT '', " +
                            "PRIMARY KEY(packageName))"
                )
            }
        }

        val INSTANCE = MainThreadInitializedObject { context ->
            Room.databaseBuilder(context, NeoLauncherDb::class.java, "NeoLauncher.db")
                //.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
        }
    }
}