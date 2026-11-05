Regenerate after Viewpoint Extended Support updates (asserts fail loudly if the original changed):
  python3 build_ves.py "<workshop>/.../ViewpointExtendedSupport.lua" ../42/media/lua/client/ViewpointExtendedSupport.lua
Equivalence test (needs `npm i fengari`): node test/run.js <orig.lua> <patched.lua> test/ves_harness.lua
Java helper: compile java/Windows.java against projectzomboid.jar + ZombieBuddy.jar, jar to 42/media/java/client/VESPerf.jar
