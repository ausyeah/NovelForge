package com.novelforge.app.data.local

import androidx.room.migration.Migration

object DatabaseMigrations {
    // Version 1 is the initial schema. Every later schema change must add an
    // explicit migration here and receive an Android migration test.
    val all: Array<Migration> = emptyArray()
}
