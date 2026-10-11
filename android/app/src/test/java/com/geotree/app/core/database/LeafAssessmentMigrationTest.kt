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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Runs the real [GeoTreeDatabase.MIGRATION_2_3_SQL] on SQLite (JVM) and checks the result against
 * Room's exported v3 schema, which is what Room validates on the device after migrating.
 */
class LeafAssessmentMigrationTest {
    private lateinit var db: Connection

    private fun schema(version: Int): JsonObject {
        val file = File("schemas/com.geotree.app.core.database.GeoTreeDatabase/$version.json")
        return Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.createFromSchema(version: Int, table: String) {
        val entity = schema(version)["entities"]!!.jsonArray.map { it.jsonObject }
            .first { it["tableName"]!!.jsonPrimitive.content == table }
        exec(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
        entity["indices"]?.jsonArray?.forEach { exec(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
    }

    /** name → (type, notNull, primaryKey), exactly what Room's TableInfo compares. */
    private fun Connection.columns(table: String): Map<String, Triple<String, Boolean, Int>> =
        createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                buildMap { while (rs.next()) put(rs.getString("name"), Triple(rs.getString("type"), rs.getInt("notnull") == 1, rs.getInt("pk"))) }
            }
        }

    private fun Connection.indices(table: String): Map<String, List<String>> {
        val names = createStatement().use { st ->
            st.executeQuery("PRAGMA index_list(`$table`)").use { rs ->
                buildList { while (rs.next()) if (rs.getString("origin") == "c") add(rs.getString("name")) }
            }
        }
        return names.associateWith { name ->
            createStatement().use { st -> st.executeQuery("PRAGMA index_info(`$name`)").use { rs -> buildList { while (rs.next()) add(rs.getString("name")) } } }
        }
    }

    private fun insertV2(id: String, tree: String, status: String, uncertain: Int?, created: Long, assessed: Long?, className: String? = "Spotting") {
        db.prepareStatement(
            "INSERT INTO leaf_assessments (id, treeId, imageLocalPath, status, classId, className, confidence, uncertain, model, " +
                "modelDisplayName, modelVersion, errorCode, errorMessage, createdAt, assessedAt) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ).use { ps ->
            val done = status == "COMPLETE"
            ps.setString(1, id); ps.setString(2, tree); ps.setString(3, "/data/leaf_images/$id.jpg"); ps.setString(4, status)
            ps.setString(5, if (done) "class-1" else null); ps.setString(6, if (done) className else null)
            if (done) ps.setDouble(7, 0.94) else ps.setNull(7, java.sql.Types.REAL)
            if (uncertain != null) ps.setInt(8, uncertain) else ps.setNull(8, java.sql.Types.INTEGER)
            ps.setString(9, if (done) "dinov2_vits14" else null); ps.setString(10, if (done) "DINOv2 ViT-S/14" else null)
            if (done) ps.setInt(11, 2) else ps.setNull(11, java.sql.Types.INTEGER)
            ps.setString(12, if (status == "FAILED") "UNREACHABLE" else null); ps.setString(13, if (status == "FAILED") "offline" else null)
            ps.setLong(14, created)
            if (assessed != null) ps.setLong(15, assessed) else ps.setNull(15, java.sql.Types.INTEGER)
            ps.executeUpdate()
        }
    }

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createFromSchema(2, "trees")
        db.createFromSchema(2, "leaf_assessments")
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `migrated table matches the exported v3 schema exactly`() {
        GeoTreeDatabase.MIGRATION_2_3_SQL.forEach { db.exec(it) }

        val fresh = DriverManager.getConnection("jdbc:sqlite::memory:").apply { createFromSchema(3, "leaf_assessments") }
        fresh.use {
            assertEquals(it.columns("leaf_assessments"), db.columns("leaf_assessments"))
            assertEquals(it.indices("leaf_assessments"), db.indices("leaf_assessments"))
        }
        assertEquals(3, schema(3)["version"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `every v2 scan survives with its status mapped and nothing invented`() {
        insertV2("a-completed", "tree-1", "COMPLETE", uncertain = 0, created = 1_000, assessed = 1_400)
        insertV2("b-uncertain", "tree-1", "COMPLETE", uncertain = 1, created = 2_000, assessed = 2_300)
        insertV2("c-failed", "tree-1", "FAILED", uncertain = null, created = 3_000, assessed = null)
        insertV2("d-interrupted", "tree-2", "PENDING", uncertain = null, created = 4_000, assessed = null)

        GeoTreeDatabase.MIGRATION_2_3_SQL.forEach { db.exec(it) }

        data class Row(val status: String, val sync: String, val completed: Long?, val updated: Long, val model: String?, val version: Int?, val error: String?, val image: String?)
        val rows = db.createStatement().use { st ->
            st.executeQuery("SELECT * FROM leaf_assessments ORDER BY createdAt").use { rs ->
                buildMap {
                    while (rs.next()) {
                        put(
                            rs.getString("id"),
                            Row(
                                rs.getString("status"), rs.getString("syncStatus"),
                                rs.getLong("completedAt").takeUnless { rs.wasNull() }, rs.getLong("updatedAt"),
                                rs.getString("modelName"), rs.getInt("modelVersion").takeUnless { rs.wasNull() },
                                rs.getString("errorCode"), rs.getString("imageLocalPath"),
                            ),
                        )
                    }
                }
            }
        }
        assertEquals(4, rows.size)
        assertEquals(Row("COMPLETED", "PENDING", 1_400, 1_400, "dinov2_vits14", 2, null, "/data/leaf_images/a-completed.jpg"), rows["a-completed"])
        assertEquals("UNCERTAIN", rows["b-uncertain"]!!.status)
        assertEquals(Row("FAILED", "PENDING", null, 3_000, null, null, "UNREACHABLE", "/data/leaf_images/c-failed.jpg"), rows["c-failed"])
        // A request that never finished becomes a FAILED history entry; no model version is invented.
        val interrupted = rows["d-interrupted"]!!
        assertEquals("FAILED", interrupted.status)
        assertEquals("INTERRUPTED", interrupted.error)
        assertNull(interrupted.version)
        assertEquals("/data/leaf_images/d-interrupted.jpg", interrupted.image)
    }

    @Test
    fun `trees table is untouched`() {
        val before = db.columns("trees") to db.indices("trees")
        GeoTreeDatabase.MIGRATION_2_3_SQL.forEach { db.exec(it) }
        assertEquals(before, db.columns("trees") to db.indices("trees"))
    }
}
