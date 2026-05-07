export type TimerStatus = "stopped" | "running" | "paused";
export type TimerPhase = "work" | "short" | "long";

export type TimerState = {
  status: TimerStatus;
  phase: TimerPhase;
  next_phase: TimerPhase;
  start_time: number;
  duration: number;
  remaining: number;
  completed: number;
  daily_goal: number;
  date: string;
  last_action_time: number;
  version: number;
};

export type ClientConfig = {
  phone_url: string;
  pairing_token: string;
};

export type CacheState = {
  fetched_at: string;
  state: TimerState;
};

export type CommandResponse = {
  success: boolean;
  state?: TimerState;
  error?: string;
};
