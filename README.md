# VelociBoard

VelociBoard is a sidebar scoreboard plugin for Velocity networks. The proxy owns the scoreboard; backend servers do not need to install it.

This project is in early development. The current build loads a configuration file and provides `/velociboard reload`. It does not display a scoreboard yet.

## Build

Use Java 25 or newer and run:

```sh
./gradlew build
```

The plugin jar is written to `build/libs/`.

## Install

Put the jar in the Velocity proxy's `plugins/` directory and restart the proxy. The plugin creates `plugins/velociboard/config.yml` on first startup. Edit the file and run `/velociboard reload` to read it again. Reload requires `velociboard.reload`.
