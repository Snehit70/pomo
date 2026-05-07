#!/usr/bin/env node
import { setTimeout as sleep } from "node:timers/promises";
import { extend, getStatus, postCommand } from "./api.js";
import { cachePath, configPath, readCache, readConfig, writeCache, writeConfig } from "./config.js";
import { formatHuman, formatWaybar } from "./format.js";
import { installService, serviceFilePath, serviceStatus, serviceTemplate, startService, stopService } from "./service.js";
import { parseClientConfig } from "./validate.js";
import qrcode from "qrcode-terminal";

type OutputMode = "human" | "json" | "waybar";

function usage(): string {
  return `pomo <command>

Commands:
  pair <url> <token>        Save phone pairing details
  pair-json '<payload>'     Save the JSON payload shown by Android pairing
  qr                        Print the saved phone pairing as a terminal QR
  status [--json|--waybar]  Print current phone state, falling back to stale cache
  toggle                   Start, pause, or resume on the phone
  skip                     Skip to next phase on the phone
  reset                    Reset current phase on the phone
  extend <minutes>         Extend current timer on the phone
  watch                    Poll phone state and refresh the stale cache
  service-template         Print launchd/systemd template
  service install          Write and enable the user service
  service start            Start the user service
  service stop             Stop the user service
  service status           Print user service status
  paths                    Print config/cache paths
`;
}

function outputMode(args: string[]): OutputMode {
  if (args.includes("--json")) return "json";
  if (args.includes("--waybar")) return "waybar";
  return "human";
}

function printState(mode: OutputMode, state: Awaited<ReturnType<typeof getStatus>>, offline = false): void {
  if (mode === "json") {
    console.log(JSON.stringify({ offline, state }));
    return;
  }
  if (mode === "waybar") {
    console.log(formatWaybar(state, offline));
    return;
  }
  console.log(formatHuman(state));
}

async function status(args: string[]): Promise<void> {
  const mode = outputMode(args);
  try {
    const config = await readConfig();
    const state = await getStatus(config);
    await writeCache(state);
    printState(mode, state);
  } catch (error) {
    const cached = await readCache();
    if (cached === undefined) {
      throw error;
    }
    if (mode === "json") {
      console.log(JSON.stringify({ offline: true, cached_at: cached.fetched_at, state: cached.state }));
      return;
    }
    if (mode === "waybar") {
      console.log(formatWaybar(cached.state, true));
      return;
    }
    console.log(formatHuman(cached.state, cached));
  }
}

async function command(name: "toggle" | "skip" | "reset"): Promise<void> {
  const config = await readConfig();
  const state = await postCommand(config, name);
  await writeCache(state);
  printState("human", state);
}

async function extendCommand(minutesArg: string | undefined): Promise<void> {
  const minutes = Number(minutesArg);
  if (!Number.isInteger(minutes) || minutes <= 0) {
    throw new Error("extend requires a positive whole number of minutes.");
  }
  const config = await readConfig();
  const state = await extend(config, minutes);
  await writeCache(state);
  printState("human", state);
}

async function watch(): Promise<void> {
  for (;;) {
    try {
      const config = await readConfig();
      await writeCache(await getStatus(config));
    } catch {
      // The service cache is display-only, so transient phone/network failures are harmless.
    }
    await sleep(5_000);
  }
}

async function main(argv: string[]): Promise<void> {
  const [cmd, ...args] = argv;
  switch (cmd) {
    case "pair": {
      const [url, token] = args;
      const config = parseClientConfig({ phone_url: url, pairing_token: token });
      await writeConfig(config);
      console.log(`Saved phone pairing at ${configPath()}`);
      return;
    }
    case "pair-json": {
      const [payload] = args;
      if (payload === undefined) {
        throw new Error("pair-json requires the JSON pairing payload from Android.");
      }
      const parsed = JSON.parse(payload) as unknown;
      if (typeof parsed !== "object" || parsed === null || !("url" in parsed) || !("token" in parsed)) {
        throw new Error("Pairing payload must contain url and token.");
      }
      const config = parseClientConfig({ phone_url: parsed.url, pairing_token: parsed.token });
      await writeConfig(config);
      console.log(`Saved phone pairing at ${configPath()}`);
      return;
    }
    case "status":
      await status(args);
      return;
    case "qr": {
      const config = await readConfig();
      const payload = JSON.stringify({ url: config.phone_url, token: config.pairing_token });
      qrcode.generate(payload, { small: true });
      console.log(payload);
      return;
    }
    case "toggle":
    case "skip":
    case "reset":
      await command(cmd);
      return;
    case "extend":
      await extendCommand(args[0]);
      return;
    case "watch":
      await watch();
      return;
    case "service-template":
      process.stdout.write(serviceTemplate());
      return;
    case "service": {
      const [action] = args;
      switch (action) {
        case "install":
          console.log(await installService());
          return;
        case "start":
          console.log(await startService());
          return;
        case "stop":
          console.log(await stopService());
          return;
        case "status":
          console.log(await serviceStatus());
          return;
        case "path":
          console.log(serviceFilePath());
          return;
        default:
          throw new Error("service requires one of: install, start, stop, status, path");
      }
    }
    case "paths":
      console.log(JSON.stringify({ config: configPath(), cache: cachePath() }, null, 2));
      return;
    case undefined:
    case "help":
    case "--help":
    case "-h":
      console.log(usage());
      return;
    default:
      throw new Error(`Unknown command: ${cmd}\n\n${usage()}`);
  }
}

main(process.argv.slice(2)).catch((error: unknown) => {
  const message = error instanceof Error ? error.message : String(error);
  console.error(message);
  process.exitCode = 1;
});
