import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { homedir, platform } from "node:os";
import type { CacheState, ClientConfig, TimerState } from "./types.js";
import { parseClientConfig, parseTimerState } from "./validate.js";

const appName = "pomo-remote";

export function configDir(): string {
  if (platform() === "darwin") {
    return join(homedir(), "Library", "Application Support", appName);
  }

  return join(process.env.XDG_CONFIG_HOME ?? join(homedir(), ".config"), appName);
}

export function stateDir(): string {
  if (platform() === "darwin") {
    return join(homedir(), "Library", "Application Support", appName);
  }

  return join(process.env.XDG_STATE_HOME ?? join(homedir(), ".local", "state"), appName);
}

export const configPath = (): string => join(configDir(), "desktop-client.json");
export const cachePath = (): string => join(stateDir(), "last-state.json");

export async function readConfig(): Promise<ClientConfig> {
  const raw = await readFile(configPath(), "utf8");
  return parseClientConfig(JSON.parse(raw) as unknown);
}

export async function writeConfig(config: ClientConfig): Promise<void> {
  const path = configPath();
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, `${JSON.stringify(config, null, 2)}\n`, { encoding: "utf8", mode: 0o600 });
}

export async function readCache(): Promise<CacheState | undefined> {
  try {
    const raw = await readFile(cachePath(), "utf8");
    const parsed = JSON.parse(raw) as unknown;
    if (typeof parsed !== "object" || parsed === null || !("fetched_at" in parsed) || !("state" in parsed)) {
      return undefined;
    }
    if (typeof parsed.fetched_at !== "string") {
      return undefined;
    }
    return { fetched_at: parsed.fetched_at, state: parseTimerState(parsed.state) };
  } catch {
    return undefined;
  }
}

export async function writeCache(state: TimerState): Promise<void> {
  const path = cachePath();
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, `${JSON.stringify({ fetched_at: new Date().toISOString(), state }, null, 2)}\n`, "utf8");
}
