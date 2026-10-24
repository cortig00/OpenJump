package com.openjump.fvp.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Localiza el catalogo dorado independientemente del working dir del test. */
internal fun resolveGoldenPath(): String {
    val candidates = listOf(
        "testdata/jump_video/catalog/golden_set.json",
        "../testdata/jump_video/catalog/golden_set.json",
        "../../testdata/jump_video/catalog/golden_set.json",
        "/srv/projects/openjump-dev/testdata/jump_video/catalog/golden_set.json",
        "fvp-lab/../testdata/jump_video/catalog/golden_set.json",
    ) + listOfNotNull(System.getProperty("user.dir")?.let { "$it/testdata/jump_video/catalog/golden_set.json" }) +
        listOfNotNull(System.getProperty("user.dir")?.let { "$it/../testdata/jump_video/catalog/golden_set.json" })
    for (c in candidates) {
        if (File(c).isFile) return c
    }
    throw IllegalStateException("golden_set.json not found; tried $candidates; cwd=${System.getProperty("user.dir")}")
}

internal fun resolveLabPath(name: String): String {
    val candidates = listOf(
        "docs/research/fvp/lab/$name",
        "../docs/research/fvp/lab/$name",
        "../../docs/research/fvp/lab/$name",
        "/srv/projects/openjump-dev/docs/research/fvp/lab/$name",
    ) + listOfNotNull(System.getProperty("user.dir")?.let { "$it/docs/research/fvp/lab/$name" }) +
        listOfNotNull(System.getProperty("user.dir")?.let { "$it/../docs/research/fvp/lab/$name" })
    for (c in candidates) {
        if (File(c).isFile) return c
    }
    throw IllegalStateException("$name not found; tried $candidates; cwd=${System.getProperty("user.dir")}")
}

class LabGoldenAdapterTest {

    @Test
    fun adapter_loads22TrialsWithExpectedKeys() {
        val trials = LabGoldenAdapter.load(resolveGoldenPath())
        assertEquals(22, trials.size)
        // Claves estables y campos PTS/alturas presentes.
        val byKey = trials.associateBy { it.trialKey }
        assertTrue(byKey.containsKey("JUMP-SJ-01__rep_01"))
        assertTrue(byKey.containsKey("JUMP-CMJ-01__rep_02"))
        val sj = byKey.getValue("JUMP-SJ-01__rep_01")
        assertEquals("JUMP-SJ-01", sj.testId)
        assertEquals("rep_01", sj.trialId)
        assertEquals("SJ", sj.protocol)
        assertEquals(2387000L, sj.takeoffUs)
        assertEquals(2921000L, sj.landingUs)
        assertEquals(34.9553135925, sj.ballisticHcm, 1e-9)
        assertEquals(35.0, sj.instrumentHcm, 1e-9)
        // Los 5 SJ estan presentes (pool de S1/S2).
        val sjKeys = trials.filter { it.protocol == "SJ" }.map { it.trialKey }.sorted()
        assertEquals(
            listOf(
                "JUMP-SJ-01__rep_01",
                "JUMP-SJ-01__rep_02",
                "JUMP-SJ-02__rep_01",
                "JUMP-SJ-03__rep_01",
                "JUMP-SJ-04__rep_01",
            ),
            sjKeys,
        )
    }

    @Test
    fun adapter_rejectsWrongSchema() {
        val bad = """
            {"schema":"com.openjump.OTRO","schema_version":1,"total_trials":0,"trials":[]}
        """.trimIndent()
        try {
            LabGoldenAdapter.parse(bad)
            fail("expected IllegalArgumentException for wrong schema")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unexpected schema"))
        }
    }

    @Test
    fun adapter_rejectsWrongCount() {
        val bad = """
            {"schema":"com.openjump.jump-golden","schema_version":1,"total_trials":22,"trials":[]}
        """.trimIndent()
        try {
            LabGoldenAdapter.parse(bad)
            fail("expected IllegalArgumentException for count mismatch")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("trials.size"))
        }
    }

    @Test
    fun seriesParser_rejectsWrongSchema() {
        val bad = """
            {"schema":"com.openjump.OTRO","schema_version":1,"id":"X","protocol":"SJ",
             "bodyMassKg":80.0,"algorithmVersion":"fvp-lab-1.0",
             "provenance":{"real":"r","synthetic":"s"},
             "conditions":[
               {"trialKey":"A","totalMassKg":80.0,"pushOffM":0.40},
               {"trialKey":"B","totalMassKg":100.0,"pushOffM":0.40}
             ]}
        """.trimIndent()
        try {
            LabSeriesParser.parse(bad)
            fail("expected IllegalArgumentException for series schema")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unexpected schema"))
        }
    }
}
