import { homedir, platform } from "node:os";
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

function homeDirectory(): string {
  const home = process.env.HOME ?? homedir();
  if (home === "") {
    throw new Error("Cannot determine the current user's home directory.");
  }
  return home;
}

function shellQuote(value: string): string {
  return `"${value.replaceAll("\\", "\\\\").replaceAll('"', '\\"')}"`;
}

function xmlEscape(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&apos;");
}

function servicePath(): string {
  const home = homeDirectory();
  if (supportedPlatform() === "darwin") {
    return join(home, "Library", "LaunchAgents", "dev.pomoremote.desktop-client.plist");
  }

  return join(process.env.XDG_CONFIG_HOME ?? join(home, ".config"), "systemd", "user", systemdUnit);
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

async function runAllowingFailure(command: string, args: string[]): Promise<string> {
  try {
    const { stdout, stderr } = await execFileAsync(command, args);
    return [stdout, stderr].filter(Boolean).join("").trim();
  } catch (error) {
    if (
      error instanceof Error &&
      "stdout" in error &&
      typeof error.stdout === "string" &&
      "stderr" in error &&
      typeof error.stderr === "string"
    ) {
      return [error.stdout, error.stderr].filter(Boolean).join("").trim() || error.message;
    }
    return error instanceof Error ? error.message : String(error);
  }
}

export function serviceTemplate(): string {
  const cliPath = join(projectRoot, "dist", "cli.js");
  const nodePath = process.execPath;
  const currentPlatform = supportedPlatform();

  if (currentPlatform === "darwin") {
    return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${launchdLabel}</string>
  <key>ProgramArguments</key>
  <array>
    <string>${xmlEscape(nodePath)}</string>
    <string>${xmlEscape(cliPath)}</string>
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
ExecStart=${shellQuote(nodePath)} ${shellQuote(cliPath)} watch
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
    try {
      await run("launchctl", ["bootstrap", launchdDomainTarget(), path]);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      if (!message.includes("service already loaded") && !message.includes("Bootstrap failed: 5")) {
        throw error;
      }
      await run("launchctl", ["bootout", launchdServiceTarget()]);
      await run("launchctl", ["bootstrap", launchdDomainTarget(), path]);
    }
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
    await run("launchctl", ["kill", "TERM", launchdServiceTarget()]).catch((error: unknown) => {
      const message = error instanceof Error ? error.message : String(error);
      console.warn(`launchctl kill failed for ${launchdServiceTarget()}: ${message}`);
    });
    return "Stopped PomoRemote desktop client.";
  }

  await run("systemctl", ["--user", "stop", systemdUnit]);
  return "Stopped PomoRemote desktop client.";
}

export async function serviceStatus(): Promise<string> {
  if (supportedPlatform() === "darwin") {
    return run("launchctl", ["print", launchdServiceTarget()]);
  }

  return runAllowingFailure("systemctl", ["--user", "status", systemdUnit, "--no-pager"]);
}
