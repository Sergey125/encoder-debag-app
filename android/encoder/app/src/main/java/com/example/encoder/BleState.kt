package com.example.encoder

sealed class BleState {
    data object Idle : BleState()
    data object Scanning : BleState()
    data object Connecting : BleState()
    data object Ready : BleState()
    data class Error(val message: String) : BleState()
}