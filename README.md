# Portal Bow

A Fabric mod for Minecraft 26.3 that adds the **Portal** enchantment for bows.

- Shoot a block to open an **orange** portal where the arrow lands.
- Hold sneak while shooting to open a **blue** one.
- Walk, fall or fire arrows into one portal to come out of the other, keeping your speed.
- Look into a portal to see what is on the other side.

Each player has one orange and one blue portal; shooting again moves that colour. A portal is one block wide and two long, and needs two solid block faces behind it.

## Getting the bow

```
/give @s bow[enchantments={"portalbow:portal":1}]
```

The enchantment also appears at the enchanting table, in villager trades and in loot.

## How the view through a portal works

- **Real view:** when the exit is in the same dimension and within render distance, the game is drawn a second time from behind the exit portal. Each portal in view costs roughly one extra render of the world; at most two are drawn per frame, and portals hidden behind solid blocks are skipped.
- **Snapshot view:** otherwise the server sends a snapshot of the blocks beyond the exit and the client ray-traces a small picture from it. It is pixelated and does not show mobs or players.

Set the environment variable `PORTALBOW_TRUE_VIEW=off` to use the snapshot view only.

## Status

- Tested on the vanilla renderer (Vulkan backend) with wall-to-wall portals.
- Not yet tested: floor and ceiling portals with the real view.
- Iris support is written against Iris 1.11.7 but **untested**. It reaches into Iris internals, so it may break on other Iris versions; if it fails, the mod falls back to the snapshot view.

## Building

Requires Java 25.

```
./gradlew build            # jar ends up in build/libs/
./gradlew runClient        # launch the game with the mod
./gradlew runClientGameTest  # automated in-game test; screenshots in build/run/clientGameTest/screenshots
```
