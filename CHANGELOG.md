# 1.1.1

- Fixed home menu buttons failing with an invalid internal command when a saved home ID contained special characters.
- Improved teleport handling across the Overworld, Nether, End, and loaded custom dimensions. Destination chunks now load before safety checks.
- Improved safe teleport checks for blocked space, missing ground, fluids, dangerous blocks, portals, and world borders, with support for slab landings.
- Teleport safety is checked again after a warmup to catch changes at the destination.
- Successful home teleports now clear velocity and fall distance.
- Failed teleports no longer report success or start the teleport cooldown.
- Failed home creation or location updates now report a storage error and restore the previous home in memory.
- Existing saved homes and configured dimension restrictions are preserved.

For Fabric 1.21.10. Replace the previous mod JAR and restart the server.
