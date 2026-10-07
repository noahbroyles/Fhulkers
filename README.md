# Fhulkers

A Paper plugin that adds the **Fhulker**: a shulker box with 54 slots (a double chest's worth) instead of 27. Regular shulker boxes are left completely vanilla.

## Requirements

- Paper (or a Paper fork such as Purpur) 26.2, built against the Paper 26.2 API
- Java 25

## Installation

Drop the plugin jar into your server's `plugins/` folder and restart. There is no configuration file.

## Building

```
mvn package
```

The jar is written to `target/Fhulkers-1.0.0.jar`.

## Crafting

Surround any shulker box with 8 copper ingots:

![Fhulker crafting recipe: a shulker box surrounded by 8 copper ingots](docs/crafting.png)

The result is that same box upgraded into a Fhulker. It keeps its color, custom name, and any items already inside. The recipe is unlocked for players when they join.

Notes:

- A box that is already a Fhulker can't be used in the recipe, so copper isn't wasted.
- Crafters (the block) can't craft Fhulkers.

## Using a Fhulker

- **Right-click** a placed Fhulker to open the 54-slot inventory. Sneak-right-click with an item in either hand to place that item against the box instead, as with vanilla.
- The item shows `54 slots, N free` in its lore.
- Fhulkers can be broken, picked up, and placed again with every item intact, including by explosions, pistons, and flowing liquids. In Creative mode the box drops only if it has contents, as in vanilla.
- If several players have the same Fhulker open, they all see one shared inventory, so items can't be duplicated.
- Shulker boxes can't be placed inside a Fhulker (vanilla rule).
- Dispensers won't place a Fhulker, because that would lose its extra slots.
- Hoppers and hopper minecarts can't move items in or out of a Fhulker while a player has it open.
- Items in the first 27 slots work with hoppers and comparators like a normal shulker box. Slots 28-54 are only reachable through the GUI.

## How it works

A Fhulker is an ordinary shulker box item or block with a `fhulkers:fhulker` marker in its persistent data. Slots 1-27 use the vanilla inventory (so hoppers, comparators, the tooltip, and dyeing keep working). Slots 28-54 are serialized next to the marker, and both move between the item and the block when it is placed or broken.

## Upgrading from a version with `rows`

Older versions had a `rows` option in `config.yml`. It has been removed and every Fhulker now has 54 slots. The old `config.yml` is ignored and can be deleted.
