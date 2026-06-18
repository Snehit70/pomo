import type { ClientConfig, TimerState } from "./types.js";
import { parseCommandResponse, parseTimerState } from "./validate.js";

const defaultRequestTimeoutMs = 10_000;

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text();
  if (text.length === 0) {
    return {};
  }
  return JSON.parse(text) as unknown;
}

async function request(config: ClientConfig, path: string, init: RequestInit = {}): Promise<unknown> {
  const controller = new AbortController();
  const timeoutMs = config.request_timeout_ms ?? defaultRequestTimeoutMs;
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  const externalSignal = init.signal;
  const abortFromExternalSignal = (): void => controller.abort();

  if (externalSignal?.aborted) {
    controller.abort();
  } else {
    externalSignal?.addEventListener("abort", abortFromExternalSignal, { once: true });
  }

  try {
    const response = await fetch(`${config.phone_url}${path}`, {
      ...init,
      signal: controller.signal,
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
  } catch (error) {
    if (error instanceof Error && error.name === "AbortError") {
      throw new Error(`Phone API request timed out after ${timeoutMs}ms.`);
    }
    throw error;
  } finally {
    clearTimeout(timeout);
    externalSignal?.removeEventListener("abort", abortFromExternalSignal);
  }
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

export async function extend(config: ClientConfig, secondsDelta: number): Promise<TimerState> {
  const body = parseCommandResponse(
    await request(config, "/api/extend", {
      method: "POST",
      body: JSON.stringify({ seconds_delta: secondsDelta })
    })
  );
  if (!body.success || body.state === undefined) {
    throw new Error(body.error ?? "Phone rejected extend.");
  }
  return body.state;
}
