import type { CacheState, TimerState } from "./types.js";

function formatTime(seconds: number): string {
  const safe = Math.max(0, Math.ceil(seconds));
  const minutes = Math.floor(safe / 60);
  const rest = safe % 60;
  return `${minutes}:${rest.toString().padStart(2, "0")}`;
}

export function formatHuman(state: TimerState, stale?: CacheState): string {
  const prefix = stale === undefined ? "" : `[offline, cached ${stale.fetched_at}] `;
  return `${prefix}${state.status} ${state.phase} ${formatTime(state.remaining)} (${state.completed}/${state.daily_goal})`;
}

export function formatWaybar(state: TimerState, offline: boolean): string {
  const icon = offline ? "!" : state.status === "running" ? ">" : state.status === "paused" ? "||" : "[]";
  return JSON.stringify({
    text: `${icon} ${formatTime(state.remaining)}`,
    tooltip: `${state.status} ${state.phase} - ${state.completed}/${state.daily_goal} sessions`,
    class: [state.status, state.phase, offline ? "offline" : "online"].join(" ")
  });
}
