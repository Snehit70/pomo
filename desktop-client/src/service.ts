import { platform } from "node:os";
import { mkdir, writeFile } from "node:fs/promises";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const currentFile = fileURLToPath(import.meta.url);
const projectRoot = resolve(dirname(currentFile), "..");
const execFileAsync = promisify(execFile);

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

  return join(process.env.XDG_CONFIG_HOME ?? join(process.env.HOME ?? "", ".config"), "systemd", "user", "pomo-remote-desktop-client.service");
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
  <string>dev.pomoremote.desktop-client</string>
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
    const uid = process.getuid?.();
    if (uid === undefined) {
      throw new Error("Cannot determine current user id for launchctl.");
    }
    await run("launchctl", ["bootstrap", `gui/${uid}`, path]).catch(async (error: unknown) => {
      const message = error instanceof Error ? error.message : String(error);
      if (!message.includes("service already loaded")) {
        throw error;
      }
    });
    await run("launchctl", ["enable", `gui/${uid}/dev.pomoremote.desktop-client`]);
    return `Installed and loaded ${path}`;
  }

  await run("systemctl", ["--user", "daemon-reload"]);
  await run("systemctl", ["--user", "enable", "pomo-remote-desktop-client.service"]);
  return `Installed ${path}`;
}

export async function startService(): Promise<string> {
  if (supportedPlatform() === "darwin") {
    const uid = process.getuid?.();
    if (uid === undefined) {
      throw new Error("Cannot determine current user id for launchctl.");
    }
    await run("launchctl", ["kickstart", "-k", `gui/${uid}/dev.pomoremote.desktop-client`]);
    return "Started PomoRemote desktop client.";
  }

  await run("systemctl", ["--user", "start", "pomo-remote-desktop-client.service"]);
  return "Started PomoRemote desktop client.";
}

export async function stopService(): Promise<string> {
  if (supportedPlatform() === "darwin") {
    const uid = process.getuid?.();
    if (uid === undefined) {
      throw new Error("Cannot determine current user id for launchctl.");
    }
    await run("launchctl", ["bootout", `gui/${uid}/dev.pomoremote.desktop-client`]).catch(() => undefined);
    return "Stopped PomoRemote desktop client.";
  }

  await run("systemctl", ["--user", "stop", "pomo-remote-desktop-client.service"]);
  return "Stopped PomoRemote desktop client.";
}

export async function serviceStatus(): Promise<string> {
  if (supportedPlatform() === "darwin") {
    return run("launchctl", ["print", "gui/${UID}/dev.pomoremote.desktop-client".replace("${UID}", String(process.getuid?.() ?? ""))]);
  }

  return run("systemctl", ["--user", "status", "pomo-remote-desktop-client.service", "--no-pager"]);
}
