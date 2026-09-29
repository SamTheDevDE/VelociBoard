# VelociBoard

VelociBoard is a sidebar scoreboard plugin for Velocity networks. The proxy owns the scoreboard; backend servers do not need to install it.

This project is in early development. The current build displays proxy sidebars with MiniMessage formatting and native placeholders. It selects boards by backend server and priority, updates on server switches, and supports `/velociboard reload`.

Velocity does not expose scoreboard packets through its public API. For now, VelociBoard uses [VelocityScoreboardAPI](https://github.com/NEZNAMY/VelocityScoreboardAPI) to send them. VelociBoard does not require VelociTab. Sidebar lines require a Minecraft 1.20.3 or newer client.

## Build

Use Java 25 or newer and run:

```sh
./gradlew build
```

The plugin jar is written to `build/libs/`.

## Install

Install VelocityScoreboardAPI and VelociBoard in the Velocity proxy's `plugins/` directory, then restart the proxy. VelociBoard creates `plugins/velociboard/config.yml`, `animations.yml`, and example files in `plugins/velociboard/scoreboards/` on first startup. Edit the files and run `/velociboard reload` to read them again. Reload requires `velociboard.reload`.

`config.yml` contains the global switch:

```yaml
enabled: true
placeholder-refresh:
  server_online: 1000
  network_online: 1000
  ping: 5000
  luckperms_prefix: 1000
  luckperms_suffix: 1000
  luckperms_primary_group: 1000
```

Each `.yml` file in `scoreboards/` defines a board. An empty or omitted `servers` list matches every server. The highest priority matching board wins; ties use the filename in alphabetical order. For example, `scoreboards/lobby.yml` can contain:

```yaml
enabled: true
servers:
  - lobby
priority: 100
title: "<purple><bold>VelociBoard</bold></purple>"
lines:
  - ""
  - "<gray>Player"
  - "<white>%player_name%"
  - ""
  - "<gray>Server"
  - "<white>%server_name%"
```

At most 15 lines are supported. Repeated visible lines, including empty lines, work without adding spaces to make them unique.

Use `<animation:name>` in a board title or line to display an animation from `animations.yml`:

```yaml
title:
  interval: 250
  mode: bounce
  frames:
    - "<purple>V"
    - "<purple>Veloci"
    - "<purple>VelociBoard"
```

`loop` restarts after the last frame. `bounce` walks back toward the first frame. One shared task advances animations for all players.

Boards and individual lines can use a `permission` or a `condition`. Conditions compare one placeholder with a literal value using `==`, `!=`, `>`, `<`, `>=`, or `<=`. The ordering operators require a number on the right. For example:

```yaml
condition: "%server_name% == lobby"
permission: velociboard.view.lobby
lines:
  - "<gray>Welcome"
  - text: "<red>Queue: %queue_position%"
    condition: "%queue_position% > 0"
  - text: "<gold>Staff online"
    permission: velociboard.staff
```

Condition and permission changes are checked once per second. A line that no longer matches is removed without rebuilding the objective.

Native placeholders: `%player_name%`, `%player_uuid%`, `%server_name%`, `%server_online%`, `%network_online%`, and `%ping%`. Unknown placeholders remain visible so typos are easier to spot. The refresh values are milliseconds; counts and ping use cached values and one shared refresh task. Player name, UUID, and server name update on join or server switch.

If LuckPerms is installed on Velocity, `%luckperms_prefix%`, `%luckperms_suffix%`, and `%luckperms_primary_group%` read its loaded user data. Prefix and suffix colors in legacy `&` or `§` format are supported. These placeholders show an empty value when LuckPerms is absent or the user has no value. VelociBoard does not query LuckPerms storage while rendering.

If you used the earlier single-board config, VelociBoard copies its title and lines into `scoreboards/default.yml` when the directory is first created. The old entries in `config.yml` can then be removed.
