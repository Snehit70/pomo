import type { ClientConfig, CommandResponse, TimerPhase, TimerState, TimerStatus } from "./types.js";

const statuses = new Set<TimerStatus>(["stopped", "running", "paused"]);
const phases = new Set<TimerPhase>(["work", "short", "long"]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

function isStatus(value: unknown): value is TimerStatus {
  return typeof value === "string" && statuses.has(value as TimerStatus);
}

function isPhase(value: unknown): value is TimerPhase {
  return typeof value === "string" && phases.has(value as TimerPhase);
}

export function parseTimerState(value: unknown): TimerState {
  if (!isRecord(value)) {
    throw new Error("Phone returned an invalid timer state.");
  }

  const state = value;
  if (
    !isStatus(state.status) ||
    !isPhase(state.phase) ||
    !isPhase(state.next_phase) ||
    !isNumber(state.start_time) ||
    !isNumber(state.duration) ||
    !isNumber(state.remaining) ||
    !isNumber(state.completed) ||
    !isNumber(state.daily_goal) ||
    typeof state.date !== "string" ||
    !isNumber(state.last_action_time) ||
    !isNumber(state.version)
  ) {
    throw new Error("Phone returned a timer state with unexpected fields.");
  }

  return {
    status: state.status,
    phase: state.phase,
    next_phase: state.next_phase,
    start_time: state.start_time,
    duration: state.duration,
    remaining: state.remaining,
    completed: state.completed,
    daily_goal: state.daily_goal,
    date: state.date,
    last_action_time: state.last_action_time,
    version: state.version
  };
}

export function parseCommandResponse(value: unknown): CommandResponse {
  if (!isRecord(value) || typeof value.success !== "boolean") {
    throw new Error("Phone returned an invalid command response.");
  }

  const response: CommandResponse = { success: value.success };
  if (value.state !== undefined) {
    response.state = parseTimerState(value.state);
  }
  if (typeof value.error === "string") {
    response.error = value.error;
  }
  return response;
}

export function parseClientConfig(value: unknown): ClientConfig {
  if (!isRecord(value) || typeof value.phone_url !== "string" || typeof value.pairing_token !== "string") {
    throw new Error("Config must contain phone_url and pairing_token.");
  }

  return {
    phone_url: value.phone_url.replace(/\/+$/, ""),
    pairing_token: value.pairing_token
  };
}
