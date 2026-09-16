package com.mirrorremote.app

object SessionState {
    @Volatile var sessionId: String? = null
    @Volatile var role: String? = null
    @Volatile var relay: RelayClient? = null
}
