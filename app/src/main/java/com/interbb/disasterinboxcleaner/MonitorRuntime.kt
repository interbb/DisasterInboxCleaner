package com.interbb.disasterinboxcleaner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object MonitorRuntime {
    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected = _listenerConnected.asStateFlow()

    fun setListenerConnected(connected: Boolean) {
        _listenerConnected.value = connected
    }
}
