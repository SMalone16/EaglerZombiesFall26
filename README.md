# Eagler Zombies Fall 2026

A Paper 1.21.11 / Java 21 classroom plugin for the Pawling Eaglercraft server.

## Current gameplay

When the plugin first sees an enabled Overworld, it creates a ring of zombie temples around that world's spawn point.

Default behavior:

- **4 zombie temples** evenly spaced around world spawn
- Each temple has a **4x4 mixed-stone base**
- A centered **2x2 upper step** gives it a compact pyramid/temple shape
- **4 zombie spawners**, one on each upper outer corner
- All zombies and zombie variants are configured to **not burn in sunlight**
- Normal fire/lava damage is unchanged; only the zombie daylight-burning property is disabled

The plugin records the spawn location it generated for in `plugins/EaglerZombiesFall26/state.yml`, so restarting the server does not duplicate the temples. If the world's spawn point is changed, the plugin will generate a new set around the new spawn.

## Classroom server integration

The classroom server's session-plugin picker expects this repository to publish:

```text
dist/EaglerZombiesFall26-1.0.0.jar
```

Once the GitHub Actions build succeeds, **Eagler Zombies Fall 2026** will show as READY in the server's Classroom Plugin Lab menu.

## Configuration

After the plugin runs once, edit:

```text
plugins/EaglerZombiesFall26/config.yml
```

You can change:

- enabled worlds
- number of temples
- distance from world spawn
- maximum temple Y
- stone block palette
- zombie spawner delay
- zombies spawned per cycle
- nearby-zombie cap
- required player range
- spawner range

The defaults intentionally use blocks that the classroom's older Eaglercraft client compatibility layer can represent cleanly.

## Operator testing commands

```text
/zombietemple status
/zombietemple generate
/zombietemple spawn
```

- `status` shows the temples tracked for the current world.
- `generate` rebuilds the configured temple ring around the current world spawn.
- `spawn` creates one test temple on top of the block you are looking at.

Permission:

```text
eaglerzombies.admin
```

Defaults to server operators.

## Build

GitHub Actions builds the plugin with Java 21 and Paper 1.21.11. The current classroom-ready JAR is stored at:

```text
dist/EaglerZombiesFall26-1.0.0.jar
```
