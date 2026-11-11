Regenerate after Viewpoint Extended Support updates (asserts fail loudly if the original changed):
  python3 build_ves.py "<workshop>/.../ViewpointExtendedSupport.lua" ../42/media/lua/client/ViewpointExtendedSupport.lua
Equivalence test (needs `npm i fengari`): node test/run.js <orig.lua> <patched.lua> test/ves_harness.lua
Java helper: compile java/Windows.java against projectzomboid.jar + ZombieBuddy.jar, jar to 42/media/java/client/VESPerf.jar

STATUS 2026-11-11: the override is withdrawn. Viewpoint Extended Support is at 0.4.56 (updated
2026-11-10); the last generated override was built from 0.4.46 and a full-file override reverts
everything upstream changed since. build_ves.py still finds 23 of its 24 anchors, but that is
not enough:
  - syncConversationCursorMode / isALifeConversationOpen no longer exist upstream; the V2 gate
    and every conversationCursorOwned / uiState.conversationClosed use in the inserted blocks
    must go, and the "A-Life conversation" harness scenario with them.
  - the block replaced from updateInteractionMenuSuppression to "local tickCounter = 0" was
    rewritten upstream (ON_FOOT_CURSOR_WINDOWS, a UIManager walk in visibleGameWindow); the
    inserted replacement mirrors the 0.4.46 logic and has to be re-derived from the new one.
  - upstream added client/ViewpointExtendedSupport/PlayerHudVisibility.lua.
Port, then prove it with test/ves_harness.lua against the 0.4.56 original before regenerating.
