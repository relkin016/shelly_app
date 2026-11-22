package com.relkin.shelly.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseTest {
    @Test
    fun gen2Status() {
        val j = JSONObject(
            """{"switch:0":{"id":0,"source":"button","output":true,"apower":123.4,"voltage":229.1,"freq":50.0,"current":0.61,
               "aenergy":{"total":4567.8,"by_minute":[1,2,3],"minute_ts":1},"temperature":{"tC":41.2,"tF":106.2},
               "timer_started_at":1000.0,"timer_duration":600.0},
               "sys":{"uptime":3600,"unixtime":1100,"available_updates":{"stable":{"version":"1.5.0"}}},
               "wifi":{"sta_ip":"192.168.1.5","status":"got ip","ssid":"Home","rssi":-61}}"""
        )
        val s = Gen2Client.parseStatus(j)
        assertTrue(s.on)
        assertEquals(123.4, s.powerW!!, 1e-9)
        assertEquals(229.1, s.voltageV!!, 1e-9)
        assertEquals(4567.8, s.energyWh!!, 1e-9)
        assertEquals(41.2, s.tempC!!, 1e-9)
        assertEquals(-61, s.rssi)
        assertEquals(500L, s.timerLeftS)
        assertEquals("1.5.0", s.updateAvailable)
        assertNull(s.pf)
    }

    @Test
    fun negativePowerIsAbsolute() {
        val g2 = Gen2Client.parseStatus(JSONObject("""{"switch:0":{"output":true,"apower":-57.3,"current":-0.25}}"""))
        assertEquals(57.3, g2.powerW!!, 1e-9)
        assertEquals(0.25, g2.currentA!!, 1e-9)
        val g1 = Gen1Client.parseStatus(JSONObject("""{"meters":[{"power":-12.5}]}"""))
        assertEquals(12.5, g1.powerW!!, 1e-9)
    }

    @Test
    fun gen1Status() {
        val j = JSONObject(
            """{"relays":[{"ison":false,"has_timer":true,"timer_remaining":42,"overpower":true}],
               "meters":[{"power":0.0,"total":6000}],"temperature":30.5,"overtemperature":false,
               "wifi_sta":{"ssid":"Home","rssi":-70},"uptime":99}"""
        )
        val s = Gen1Client.parseStatus(j)
        assertFalse(s.on)
        assertEquals(100.0, s.energyWh!!, 1e-9) // 6000 Вт·мин = 100 Вт·ч
        assertEquals(42L, s.timerLeftS)
        assertEquals(listOf("overpower"), s.errors)
        assertNull(s.voltageV)
    }

    @Test
    fun info() {
        val g2 = Shelly.parseInfo(JSONObject("""{"name":null,"id":"shellyplusplugs-aabb","mac":"AABB","model":"SNPL-00112EU","gen":2,"fw_id":"x","ver":"1.4.4","app":"PlusPlugS","auth_en":true}"""))
        assertEquals(2, g2.gen); assertTrue(g2.authRequired); assertNull(g2.name); assertEquals("Shelly Plus Plug S", g2.modelName)
        val g1 = Shelly.parseInfo(JSONObject("""{"type":"SHPLG-S","mac":"AABB","auth":false,"fw":"20230913-114008/v1.14.0-gcb84623"}"""))
        assertEquals(1, g1.gen); assertFalse(g1.authRequired); assertEquals("v1.14.0-gcb84623", g1.fw)
    }

    @Test
    fun digest() {
        val ch = Digest.parse("""Digest qop="auth", realm="shellyplus1-xxx", nonce="60dc59c6", algorithm=SHA-256""")!!
        assertEquals("shellyplus1-xxx", ch.realm)
        assertEquals("auth", ch.qop)
        val h = Digest.header(ch, "admin", "pw", "GET", "/rpc/Shelly.GetStatus", 1, "abc")
        val ha1 = Digest.hash("SHA-256", "admin:shellyplus1-xxx:pw")
        val ha2 = Digest.hash("SHA-256", "GET:/rpc/Shelly.GetStatus")
        val expected = Digest.hash("SHA-256", "$ha1:60dc59c6:00000001:abc:auth:$ha2")
        assertTrue(h.contains("response=\"$expected\""))
        assertTrue(h.contains("nc=00000001"))
    }
}
