import { platform } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const currentFile = fileURLToPath(import.meta.url);
const projectRoot = resolve(dirname(currentFile), "..");

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
