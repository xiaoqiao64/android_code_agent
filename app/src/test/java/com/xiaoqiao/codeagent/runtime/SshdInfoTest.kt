package com.xiaoqiao.codeagent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshdInfoTest {
    @Test
    fun copyTextIncludesUserPasswordPortAndIp() {
        val info = SshdInfo(password = "secretPass", ips = listOf("192.168.1.8", "10.0.0.2"))
        val text = info.copyText()
        assertEquals("ssh root@192.168.1.8 -p 8022", info.command())
        assertTrue(text.contains("ssh root@192.168.1.8 -p 8022"))
        assertTrue(text.contains("user: root"))
        assertTrue(text.contains("password: secretPass"))
        assertTrue(text.contains("port: 8022"))
        assertTrue(text.contains("192.168.1.8, 10.0.0.2"))
    }

    @Test
    fun emptyIpsFallBackToLoopback() {
        val info = SshdInfo(password = "x", ips = emptyList())
        assertEquals("ssh root@127.0.0.1 -p 8022", info.command())
        assertTrue(info.copyText().contains("ip: (none"))
    }
}
