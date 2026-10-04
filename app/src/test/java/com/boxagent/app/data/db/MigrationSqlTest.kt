package com.boxagent.app.data.db

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Room validates a migrated table against the schema it generated; the
 * hand-written migration must match it exactly. Fails when SkillEntity
 * changes without updating MIGRATION_1_2_SQL (or adding a new migration).
 */
class MigrationSqlTest {
    @Test
    fun migrationMatchesRoomGeneratedSchema() {
        val impl = File("build/generated/ksp/debug/java/com/boxagent/app/data/db/AppDb_Impl.java")
        assumeTrue("generated Room code not found", impl.exists())
        val generated = impl.readText()
        AppDb.MIGRATION_1_2_SQL.forEach { sql ->
            assertTrue("not in Room's schema: $sql", generated.contains("\"$sql\""))
        }
    }
}
