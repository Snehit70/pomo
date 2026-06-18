package com.pomo.crew

public object CrewDefaults {
    public const val SNAPSHOT_EVENT_KIND: Int = 39050
    public val DEFAULT_RELAYS: List<String> = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.primal.net",
    )
}
