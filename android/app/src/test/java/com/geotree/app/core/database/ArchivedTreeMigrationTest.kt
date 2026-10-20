package com.geotree.app.core.database

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Runs the real [GeoTreeDatabase.MIGRATION_3_4_SQL] on SQLite (JVM) against a v3 trees table and
 * checks the result against Room's exported v4 schema; existing field records must survive as active.
 */
class ArchivedTreeMigrationTest {
    private lateinit var db: Connection

    private fun entity(version: Int, table: String): JsonObject {
        val file = File("schemas/com.geotree.app.core.database.GeoTreeDatabase/$version.json")
        val database = Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
        return database["entities"]!!.jsonArray.map { it.jsonObject }.first { it["tableName"]!!.jsonPrimitive.content == table }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    /** name â†’ (type, notNull, default), what Room's TableInfo compares for this column. */
    private fun Connection.columns(table: String): Map<String, Triple<String, Boolean, String?>> =
        createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                buildMap { while (rs.next()) put(rs.getString("name"), Triple(rs.getString("type"), rs.getInt("notnull") == 1, rs.getString("dflt_value"))) }
            }
        }

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        val v3 = entity(3, "trees")
        db.exec(v3["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "trees"))
        db.exec(
            "INSERT INTO trees (id, treeCode, localImagePath, remoteImagePath, latitude, longitude, accuracyMeters, altitudeMeters, " +
                "locationCapturedAt, age, tasteCategory, yearlyYield, fruitQuality, notes, createdAt, updatedAt, syncStatus, serverVersion, lastSyncError) " +
                "VALUES ('uuid-1', 'GEO-TAM-001', '/data/a.jpg', NULL, 15.1, 120.6, 4.0, NULL, 1, NULL, NULL, NULL, NULL, 'kept', 1, 2, 'PENDING', NULL, NULL)",
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `migration adds archived with default 0 and keeps every tree`() {
        GeoTreeDatabase.MIGRATION_3_4_SQL.forEach { db.exec(it) }

        val expected = DriverManager.getConnection("jdbc:sqlite::memory:").use { v4 ->
            v4.exec(entity(4, "trees")["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "trees"))
            v4.columns("trees")
        }
        assertEquals(expected, db.columns("trees"))
        db.createStatement().use { st ->
            st.executeQuery("SELECT treeCode, notes, syncStatus, archived FROM trees").use { rs ->
                rs.next()
                assertEquals("GEO-TAM-001", rs.getString(1))
                assertEquals("kept", rs.getString(2))
                assertEquals("PENDING", rs.getString(3))
                assertEquals(0, rs.getInt(4))
            }
        }
    }
}
