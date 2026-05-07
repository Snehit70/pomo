package com.pomoremote.models

public data class Session(
    val type: String,
    val start: Long,
    val duration: Int,
    val completed: Boolean,
)
