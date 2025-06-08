# Mabel

Mabel is a Matrix bot that listens for events from a Frigate NVR system over MQTT and reports them to a Matrix room.

## Features

- Listens for person detection events from Frigate
- Posts a message and snapshot to a Matrix room when a person is detected
- Allows muting/unmuting notifications per camera or globally via Matrix room commands
- Avoids duplicate notifications by keeping a cache of recently seen snapshots

## Prerequisites

- A running Frigate NVR system publishing MQTT events 
- A Matrix homeserver and registered user account for the bot
- Clojure CLI tools

## Configuration

The bot is configured via command line arguments:

```
  -v, --verbose                    Provide verbose output.
  -h, --help                       Print this message.
      --mqtt-host HOSTNAME         Hostname of MQTT server on which to listen for events.
      --mqtt-port PORT             Port on which to connect to the incoming MQTT server.
      --mqtt-user USER             User as which to connect to MQTT server.
      --mqtt-password-file PASSWD_FILE  File containing password for MQTT user.
      --matrix-domain DOMAIN       Domain of Matrix server.
      --matrix-user USER           User as which to connect to Matrix server.
      --matrix-password-file PASSWD_FILE  File containing Matrix user password.
      --matrix-room ROOM           Room in which to report events.
```

## Running

First, install the necessary dependencies:

```bash
clojure -P
```

Then run the bot:

```bash
clojure -M:run --mqtt-host localhost --mqtt-port 1883 --mqtt-user user --mqtt-password-file mqtt.passwd \
              --matrix-domain matrix.org --matrix-user bot --matrix-password-file matrix.passwd \
              --matrix-room '!room:matrix.org'
```

Adjust the arguments as needed for your environment.

## Commands

The bot supports the following commands when mentioned in the Matrix room:

- `@bot: silence <camera> [<duration>]` - Silence events from a specific camera for an optional duration (default 10 minutes)
- `@bot: silence all [<duration>]` - Silence events from all cameras for an optional duration  
- `@bot: unmute <camera>` - Resume notifications for a specific camera
- `@bot: unmute all` - Resume notifications for all cameras

Where `<duration>` is a number followed by a unit, e.g. `30 seconds`, `10 minutes`, `1 hour`, etc.

## Implementation Details

The high-level flow of the application is:

1. Connect to Matrix and MQTT servers using provided credentials  
2. Subscribe to the Frigate events MQTT topic
3. Filter events to only include person detections
4. For each person detection:
   - Retrieve the associated snapshot image over MQTT
   - If the camera is not currently muted:
     - Check the snapshot against a cache of recently seen snapshots
     - If new, post a notification message and the snapshot to the Matrix room
     - Add the snapshot to the cache
     - Mute the camera for the default duration
5. Listen for commands in the Matrix room and update the camera mute states accordingly
6. Gracefully handle disconnects and reconnect as needed

The core logic is split across a few namespaces:

- `mabel.cli` - Entrypoint and configuration parsing
- `mabel.core` - Main application logic and Matrix/MQTT connection handling
- `mabel.handlers` - Handlers for different event types (detections, commands, etc)
- `mabel.utils` - Utility functions for time parsing, caching, etc

## License

Copyright © 2023 Your Name

Distributed under the Eclipse Public License version 1.0.
