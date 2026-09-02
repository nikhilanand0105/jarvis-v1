package com.jarvispoc.flows

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramAutoReplyFlowTest {

    @Test
    fun testSensitiveMessageBlocking() {
        val flow = TelegramAutoReplyFlow
        
        // Should block
        assertTrue(flow.isSensitiveMessage("Your OTP is 1234"))
        assertTrue(flow.isSensitiveMessage("Use this PIN: 9876"))
        assertTrue(flow.isSensitiveMessage("Your password has been reset"))
        assertTrue(flow.isSensitiveMessage("bank account alert"))
        assertTrue(flow.isSensitiveMessage("your verification code is 5555"))
        
        // Should allow
        assertFalse(flow.isSensitiveMessage("Hey how are you?"))
        assertFalse(flow.isSensitiveMessage("Can we meet tomorrow?"))
        assertFalse(flow.isSensitiveMessage("I sent you the file"))
        assertFalse(flow.isSensitiveMessage("Just a regular message without any sensitive keywords"))
    }
}
