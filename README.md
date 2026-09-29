# VelociBoard

VelociBoard is a sidebar scoreboard plugin for Velocity networks. The proxy owns the scoreboard; backend servers do not need to install it.

This project is in early development. The current build displays one sidebar with MiniMessage formatting and `%player_name%` and `%server_name%` placeholders. It updates on server switches and supports `/velociboard reload`.

Velocity does not expose scoreboard packets through its public API. For now, VelociBoard uses [VelocityScoreboardAPI](https://github.com/NEZNAMY/VelocityScoreboardAPI) to send them. VelociBoard does not require VelociTab. Sidebar lines require a Minecraft 1.20.3 or newer client.

## Build

Use Java 25 or newer and run:

```sh
./gradlew build
```

The plugin jar is written to `build/libs/`.

## Install

Install VelocityScoreboardAPI and VelociBoard in the Velocity proxy's `plugins/` directory, then restart the proxy. VelociBoard creates `plugins/velociboard/config.yml` on first startup. Edit the file and run `/velociboard reload` to read it again. Reload requires `velociboard.reload`.

```yaml
enabled: true
title: "<purple><bold>VelociBoard</bold></purple>"
lines:
  - ""
  - "<gray>Player"
  - "<white>%player_name%"
  - ""
  - "<gray>Server"
  - "<white>%server_name%"
```

At most 15 lines are supported.
