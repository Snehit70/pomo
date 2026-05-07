import { platform } from "node:os";
import { mkdir, writeFile } from "node:fs/promises";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const currentFile = fileURLToPath(import.meta.url);
const projectRoot = resolve(dirname(currentFile), "..");
const execFileAsync = promisify(execFile);
const launchdLabel = "dev.pomoremote.desktop-client";
const systemdUnit = "pomo-remote-desktop-client.service";

type ServicePlatform = "darwin" | "linux";

function supportedPlatform(): ServicePlatform {
  const current = platform();
  if (current === "darwin" || current === "linux") {
    return current;
  }
  throw new Error(`Service management is not supported on ${current}.`);
}

function servicePath(): string {
  if (supportedPlatform() === "darwin") {
    return join(process.env.HOME ?? "", "Library", "LaunchAgents", "dev.pomoremote.desktop-client.plist");
  }

  return join(process.env.XDG_CONFIG_HOME ?? join(process.env.HOME ?? "", ".config"), "systemd", "user", systemdUnit);
}

function currentUid(): number {
  const uid = process.getuid?.();
  if (uid === undefined) {
    throw new Error("Cannot determine current user id for launchctl.");
  }
  return uid;
}

function launchdServiceTarget(): string {
  return `gui/${currentUid()}/${launchdLabel}`;
}

function launchdDomainTarget(): string {
  return `gui/${currentUid()}`;
}

async function run(command: string, args: string[]): Promise<string> {
  try {
    const { stdout, stderr } = await execFileAsync(command, args);
    return [stdout, stderr].filter(Boolean).join("").trim();
  } catch (error) {
    if (error instanceof Error && "stderr" in error && typeof error.stderr === "string") {
      throw new Error(error.stderr.trim() || error.message);
    }
    throw error;
  }
}

export function serviceTemplate(): string {
  const cliPath = join(projectRoot, "dist", "cli.js");

  if (platform() === "darwin") {
    return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${launchdLabel}</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/bin/env</string>
    <string>node</string>
    <string>${cliPath}</string>
    <string>watch</string>
  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
</dict>
</plist>
`;
  }

  return `[Unit]
Description=PomoRemote desktop client
After=network-online.target

[Service]
ExecStart=/usr/bin/env node ${cliPath} watch
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
`;
}

export function serviceFilePath(): string {
  return servicePath();
}

export async function installService(): Promise<string> {
  const path = servicePath();
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, serviceTemplate(), "utf8");

  if (supportedPlatform() === "darwin") {
    await run("launchctl", ["bootstrap", launchdDomainTarget(), path]).catch(async (error: unknown) => {
      const message = error instanceof Error ? error.message : String(error);
      if (!message.includes("service already loaded")) {
        throw error;
      }
      await run("launchctl", ["bootout", launchdServiceTarget()]);
      await run("launchctl", ["bootstrap", launchdDomainTarget(), path]);
    });
    await run("launchctl", ["enable", launchdServiceTarget()]);
    return `Installed and loaded ${path}`;
  }

  await run("systemctl", ["--user", "daemon-reload"]);
  await run("systemctl", ["--user", "enable", systemdUnit]);
  return `Installed ${path}`;
}

export async function startService(): Promise<string> {
  if (supportedPlatform() === "darwin") {
    await run("launchctl", ["enable", launchdServiceTarget()]);
    await run("launchctl", ["kickstart", "-k", launchdServiceTarget()]);
    return "Started PomoRemote desktop client.";
  }

  await run("systemctl", ["--user", "start", systemdUnit]);
  return "Started PomoRemote desktop client.";
}

export async function stopService(): Promise<string> {
  if (supportedPlatform() === "darwin") {
    await run("launchctl", ["disable", launchdServiceTarget()]);
    await run("launchctl", ["kill", "TERM", launchdServiceTarget()]).catch(() => undefined);
    return "Stopped PomoRemote desktop client.";
  }

  await run("systemctl", ["--user", "stop", systemdUnit]);
  return "Stopped PomoRemote desktop client.";
}

export async function serviceStatus(): Promise<string> {
  if (supportedPlatform() === "darwin") {
    return run("launchctl", ["print", launchdServiceTarget()]);
  }

  return run("systemctl", ["--user", "status", systemdUnit, "--no-pager"]);
}
