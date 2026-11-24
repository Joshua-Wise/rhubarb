package dev.jwise.rhubarb.rex

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizeBaseTest {
    @Test fun schemes() {
        assertEquals("wss://mac.tail1.ts.net", RexControl.normalizeBase("mac.tail1.ts.net"))
        assertEquals("wss://mac.tail1.ts.net", RexControl.normalizeBase("https://mac.tail1.ts.net/"))
        assertEquals("ws://mac.tail1.ts.net:7399", RexControl.normalizeBase("http://mac.tail1.ts.net:7399"))
        // Bare local addresses are plain HTTP listeners.
        assertEquals("ws://192.168.1.20:7399", RexControl.normalizeBase("192.168.1.20:7399"))
        assertEquals("ws://100.101.102.103:7399", RexControl.normalizeBase("100.101.102.103:7399"))
        assertEquals("ws://studio.local:7399", RexControl.normalizeBase("studio.local:7399"))
        assertEquals("ws://localhost:7399", RexControl.normalizeBase("localhost:7399"))
        assertEquals("wss://192.168.1.20", RexControl.normalizeBase("https://192.168.1.20"))
    }
}
