# Viewpoint Living Props: performance contributions (VLP_Perf)

Upstream (workshop 3816442251, modversion 1.0.3) Lua copied unchanged into upstream/lua/client.

Only ViewpointLivingPropsClocks.lua is overridden: upstream plus a 250 ms per-clock cache of the
Viewpoint on-screen check (`onScreenCached`). The earlier map+array rewrites of the other modules
were dropped: they allocated a new table per update for no gain (and leaked/broke clock and
fireplace tracking), so those modules now load from the original mod.

On a VLP update: copy the new upstream Clocks file here, re-apply the two `onScreenCached` hunks
and bump the version in the guard at the top of the override.
