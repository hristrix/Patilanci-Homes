# Patilanci Homes 1.1 — Fabric 1.21.10 (source)

A server-side Minecraft Homes mod using native dialogs. Separate from Patilanci Auth.

## Build
Java 21: `./gradlew build` (Windows: `.\gradlew.bat build`). JAR: `build/libs/patilanci-homes-1.1.1.jar`. The build includes the home dialog command regression checks.

## Installation and upgrade
1. Stop the Minecraft server. Make backups of `world/` and `config/patilanci-homes/`.
2. Replace the *old* Patilanci Homes JAR with the new JAR in `mods/`; do not install both.
3. Start the server. It will create `config/patilanci-homes/config.yml` if missing. Existing `homes.json` remains intact.
4. Edit config.yml and run `/homes reload` in-game as OP (or via console). Existing homes are never cleared by reload.

## Settings
- `limits.normal`: number of homes ordinary players can save, default 5.
- `limits.operators`: `-1` means unlimited. Operators (permission level 2+) have unlimited homes by default and their configured limit takes priority over username overrides.
- `limits.players`: optional exact username overrides (case-insensitive); usernames are mutable, so avoid using them for long-term paid ranks.
- `max-name-length`: maximum home label length.
- `overwrite-existing`: if true `/sethome ExistingName` updates the existing location; otherwise rejects duplicate names.
- `worlds.nether` / `worlds.end`: whether players can create or update homes in those dimensions (saved homes there remain teleportable).
- `ui`: dialog labels, prefix and column count.
- `buttons`: dialog button names.
- `messages`: command feedback templates.

The file is generated on first startup. No server restart needed to change these settings. Invalid YAML will reject reload without replacing last good settings. Lowering a home limit will **not delete existing homes**; it only blocks new creations until the player's count is below limit.

## Commands
`/homes` opens dialog; `/homes reload` reloads config (OP). `/sethome [name]`, `/home <name>`, `/delhome <name>`, `/renamehome <old> <new>`, `/updatehome <name>`.

## Data preservation
Homes are stored by player UUID in `config/patilanci-homes/homes.json` and are not changed by a configuration reload. Existing offline UUIDs are respected. The old Modexa/Paper `homes.yml` import logic remains, and originals are copied rather than removed. Always take a backup before updating.

## Limitations
This release makes the main settings and dialog text configurable, **not the dialog layout/action graph**. The full Modexa YAML dialog engine and its ESC menu integration have not been ported. Permission integration for ranked players requires a separate permissions adapter; per-player overrides and OP limits are provided for now.

## 1.2 — Extended live configuration

`/homes reload` reloads `config/patilanci-homes/config.yml` without touching `homes.json`. Only operators at permission level 2+ can reload. Invalid YAML or out-of-range values leave the previous settings active.

The configuration now also supports:

- `cooldowns.create-seconds` and `cooldowns.teleport-seconds` (0 disables cooldown)
- `teleport.warmup-seconds` (0 for immediate teleport), `cancel-on-move`, `safe-location-check`, `block-cross-dimension`
- `worlds.blocked` (list of full dimension IDs, e.g. `minecraft:the_nether`)
- `actions.delete`, `actions.rename`, and `actions.update` to disable actions even through commands
- `ui.show-coordinates`, `ui.show-world` and `ui.visibility.{create,teleport,update,rename,delete}`
- `tooltips.*` for each home dialog button, `buttons.*`, and `messages.*`

Example:

```yaml
cooldowns:
  create-seconds: 5
  teleport-seconds: 30
teleport:
  warmup-seconds: 3
  cancel-on-move: true
  safe-location-check: true
  block-cross-dimension: false
worlds:
  nether: true
  end: true
  blocked: ['example:restricted_world']
actions:
  delete: true
  rename: true
  update: true
ui:
  show-coordinates: false
  show-world: true
  visibility:
    create: true
    teleport: true
    update: true
    rename: true
    delete: true
```

**Compatibility:** The original `homes.json` and its UUID-based home records remain unchanged. No saved home is deleted if a limit is reduced. New options are all optional when loading an older config file. In order to see the complete new default YAML, compare with the built-in `DEFAULT_YAML` constant and manually copy the missing sections; the mod does not overwrite existing config files.

**Development status:** Build and command regression checks are available through the Gradle wrapper. Live player teleport behavior still requires an in-game check on a copy of your server.


## User-friendly dialogs (no icons)
- `/homes` shows one button for each home. No icons, icon previews or icon selection.
- The detail menu includes teleport, update, rename, delete and back actions (subject to configuration).
- Rename opens a native text-input dialog prefilled with the existing name.
- `/delhome <name>` opens a confirmation dialog showing the home name, world and location before deletion.
- The old `icons.json` file, if present, is **left untouched** and is no longer read or modified.
- `/homes reload`, operator unlimited homes and configuration options remain intact.

## 1.1.1 dimension and command fixes

- Dialog commands quote home IDs when required. Base64 padding and legacy keys no longer break opening, teleporting, updating, renaming, or deleting homes. Existing IDs and saved dimension coordinates are preserved.
- Teleporting resolves the exact saved dimension ID, including loaded custom dimensions, and loads the landing chunks in that dimension before checking the destination. Coordinates are used exactly as saved, without Nether portal scaling.
- With `teleport.safe-location-check: true`, the full standing player must fit above supporting ground, without fluids, dangerous blocks, or portal blocks at the destination. The check supports partial blocks such as slabs and rejects unsupported End void locations. Safety is checked again after a warmup.
- Successful teleports clear velocity and fall distance. Failed teleports do not report success or consume the teleport cooldown. Failed create, overwrite, or update writes restore the previous in-memory home and report the storage error.
- Default settings allow the Overworld, Nether, End, and custom dimensions. Existing `worlds.nether`, `worlds.end`, `worlds.blocked`, and `teleport.block-cross-dimension` restrictions remain respected.

In-game verification (requires a running Fabric 1.21.10 server and player):

1. Save a named home on solid ground in each of the Overworld, Nether, and End, plus any custom dimension. Return to each from another dimension through `/home <name>` and the dialog; confirm coordinates and facing.
2. Restart the server and repeat. Update and rename a home through the dialog. With an unlimited-home player, create at least ten homes and use the tenth home's buttons (its Base64 ID contains padding).
3. Leave a destination area unloaded and teleport back. Repeat with a warmup; obstruct the destination during the delay and confirm the teleport is refused.
4. Confirm lava, fire, blocked headroom, unsupported void, and destinations outside the world border are refused. Confirm a normal slab landing works and teleporting while falling does not carry fall damage to the home.
5. Enable dimension restrictions and confirm they still apply. Existing homes must survive config reloads and server restarts.
