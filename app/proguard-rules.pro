# Gson reflects over these classes and their field names are persisted or exposed
# through the phone-owned LAN API. Keep them stable in release builds.
-keep class com.pomo.timer.TimerState { *; }
-keep class com.pomo.service.PomodoroService$ConfigPayload { *; }
-keep class com.pomo.service.PomodoroService$Durations { *; }
-keep class com.pomo.service.TimerConfigPayloads$PartialPayload { *; }
-keep class com.pomo.service.TimerConfigPayloads$PartialDurations { *; }
-keep class com.pomo.db.HistoryCacheRepository$ServerDayEntry { *; }
-keep class com.pomo.db.HistoryCacheRepository$ServerSession { *; }
-keep class com.pomo.crew.CrewJoinPayload { *; }
-keep class com.pomo.crew.CrewSnapshot { *; }
-keep class com.pomo.crew.CrewSnapshotEnvelope { *; }
-keep class com.pomo.crew.CrewMembership { *; }

# Ktor/SLF4J contain optional JVM-only debug/logging hooks that are not present
# on Android and are not used by the app at runtime.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn org.slf4j.impl.StaticLoggerBinder
