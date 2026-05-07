import type { ClientConfig, TimerState } from "./types.js";
import { parseCommandResponse, parseTimerState } from "./validate.js";

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text();
  if (text.length === 0) {
    return {};
  }
  return JSON.parse(text) as unknown;
}

async function request(config: ClientConfig, path: string, init: RequestInit = {}): Promise<unknown> {
  const response = await fetch(`${config.phone_url}${path}`, {
    ...init,
    headers: {
      "X-Pomo-Token": config.pairing_token,
      ...(init.body === undefined ? {} : { "Content-Type": "application/json" }),
      ...init.headers
    }
  });

  const body = await readJson(response);
  if (!response.ok) {
    throw new Error(`Phone API returned ${response.status}: ${JSON.stringify(body)}`);
  }

  return body;
}

export async function getStatus(config: ClientConfig): Promise<TimerState> {
  return parseTimerState(await request(config, "/api/status"));
}

export async function postCommand(config: ClientConfig, command: "toggle" | "skip" | "reset"): Promise<TimerState> {
  const body = parseCommandResponse(await request(config, `/api/${command}`, { method: "POST" }));
  if (!body.success || body.state === undefined) {
    throw new Error(body.error ?? `Phone rejected ${command}.`);
  }
  return body.state;
}

export async function extend(config: ClientConfig, minutes: number): Promise<TimerState> {
  const body = parseCommandResponse(
    await request(config, "/api/extend", {
      method: "POST",
      body: JSON.stringify({ minutes })
    })
  );
  if (!body.success || body.state === undefined) {
    throw new Error(body.error ?? "Phone rejected extend.");
  }
  return body.state;
}
