package com.boxlabs.hexdroid.data

/**
 * Entry for /LIST channel directory.
 */
data class ChannelListEntry(
    val channel: String,
    val users: Int,
    val topic: String
)
