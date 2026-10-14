package com.spaceaudits.stardeck

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class PlanTest {
    private val m31 = PlanItem(1001, "M 31", "NGC 224", 0.712, 41.269, planStartMin(23, 0), 60, lpFilter = false)
    private val m42 = PlanItem(1002, "M 42", "NGC 1976", 5.588, -5.391, planStartMin(21, 30), 45, lpFilter = true, mosaic = Mosaic(1.5, 30.0))

    private fun date(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, 12, 0) }.time

    @Test
    fun startMinutesRollPastMidnight() {
        assertEquals(1290, planStartMin(21, 30))
        assertEquals(1440 + 90, planStartMin(1, 30))
        assertEquals("01:30", formatStartMin(1530))
        assertEquals(1530, parseStartMin("01:30"))
        assertNull(parseStartMin("25:00"))
        assertNull(parseStartMin("9"))
    }

    @Test
    fun buildsSetViewPlanParams() {
        val p = planParams("Autumn", date(2026, 10, 14), listOf(m31, m42))
        assertEquals("Autumn", p.getString("plan_name"))
        assertEquals("2026-10-14", p.getString("update_time_seestar"))
        val list = p.getJSONArray("list")
        assertEquals(2, list.length())
        val first = list.getJSONObject(0)
        assertEquals("sorted by start", "M 42", first.getString("target_name"))
        assertEquals("NGC 1976", first.getString("alias_name"))
        assertEquals(1002L, first.getLong("target_id"))
        assertEquals(1290, first.getInt("start_min"))
        assertEquals(45, first.getInt("duration_min"))
        assertTrue(first.getBoolean("lp_filter"))
        assertEquals(5.588, first.getJSONArray("target_ra_dec").getDouble(0), 0.0)
        assertEquals(-5.391, first.getJSONArray("target_ra_dec").getDouble(1), 0.0)
        val mosaic = first.getJSONObject("mosaic")
        assertEquals(1.5, mosaic.getDouble("scale"), 0.0)
        assertEquals(30.0, mosaic.getDouble("angle"), 0.0)
        assertEquals(0.0, mosaic.getDouble("star_map_angle"), 0.0)
        assertFalse("no mosaic key without a mosaic", list.getJSONObject(1).has("mosaic"))
    }

    @Test
    fun leavesOutAOneTimesMosaic() {
        val p = planParams("x", date(2026, 1, 2), listOf(m31.copy(mosaic = Mosaic(1.0, 0.0))))
        assertFalse(p.getJSONArray("list").getJSONObject(0).has("mosaic"))
    }

    @Test
    fun parsesTheScopePlan() {
        val app = JSONObject(
            """{"state":"working","lapse_ms":1260000,"plan":{"plan_name":"Autumn","update_time_seestar":"2026-10-14","list":[
               {"target_id":1002,"target_name":"M 42","alias_name":"NGC 1976","target_ra_dec":[5.588,-5.391],"start_min":1290,"duration_min":45,"lp_filter":true,
                "mosaic":{"scale":1.5,"angle":30,"star_map_angle":0},"state":"working","stack_total_sec":420.0},
               {"target_id":1001,"target_name":"M 31","alias_name":"NGC 224","target_ra_dec":[0.712,41.269],"start_min":1380,"duration_min":60,"lp_filter":false}]}}"""
        )
        val plan = parseViewPlan(app)!!
        assertEquals("Autumn", plan.name)
        assertEquals("working", plan.state)
        assertEquals(1260000L, plan.lapseMs)
        assertEquals(2, plan.items.size)
        assertEquals(m42.copy(state = "working", stackTotalSec = 420.0), plan.items[0])
        assertEquals("", plan.items[1].state)
        assertNull(plan.items[1].stackTotalSec)

        // a sent plan reads back the same
        val again = parseViewPlan(JSONObject().put("plan", planParams(plan.name, date(2026, 10, 14), plan.items)))!!
        assertEquals(plan.items.map { it.copy(state = "", stackTotalSec = null) }, again.items)
        assertNull(parseViewPlan(null))
    }

    @Test
    fun buildsMosaicGotoParams() {
        val p = startViewParams("M 31", 0.75, 41.4, false, false, Mosaic(2.0, -45.0))
        assertEquals("star", p.getString("mode"))
        assertEquals("View", p.getString("selected_cam"))
        assertEquals(2.0, p.getJSONObject("mosaic").getDouble("scale"), 0.0)
        assertEquals(-45.0, p.getJSONObject("mosaic").getDouble("angle"), 0.0)
        assertFalse(startViewParams("M 31", 0.75, 41.4, false, true, Mosaic()).has("mosaic"))
        assertEquals("SecondView", startViewParams("M 31", 0.75, 41.4, false, true, null).getString("selected_cam"))
    }
}
