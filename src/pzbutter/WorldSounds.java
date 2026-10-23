package pzbutter;

import java.util.HashSet;
import java.util.List;
import zombie.GameTime;
import zombie.WorldSoundManager;
import zombie.WorldSoundManager.ResultBiggestSound;
import zombie.WorldSoundManager.WorldSound;
import zombie.characters.IsoZombie;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.areas.IsoRoom;
import zombie.network.GameServer;

/**
 * Plan 4 world sounds: worldSoundCleanupFast (WorldSoundManager.update), hearingHoist (getBiggestSoundZomb) and the
 * worldSoundFast fish memo (FishSchoolManager.addSoundNoise). Main thread for update; the other two may run anywhere.
 */
final class WorldSounds {
    private WorldSounds() {}

    /** Set by an expired sound's countdown; the next update sweeps the chunks before it releases that sound. */
    private static boolean sweepNeeded;

    private static final ThreadLocal<ResultBiggestSound> result = ThreadLocal.withInitial(ResultBiggestSound::new);

    private record FishKey(int x, int y, int r) {}
    private static final HashSet<FishKey> fishMemo = new HashSet<>();
    private static long fishMinute = Long.MIN_VALUE;

    static void init() {
        SoundPatches.cleanupGuard.on = Settings.flag("worldSoundCleanupFast");
        SoundPatches.hearingGuard.on = Settings.flag("hearingHoist");
        SoundPatches.fishGuard.on = Settings.flag("worldSoundFast");
    }

    /** Test hook: a fresh state. */
    static void resetForTest() {
        sweepNeeded = false;
        synchronized (fishMemo) {
            fishMemo.clear();
            fishMinute = Long.MIN_VALUE;
        }
    }

    /** Does this frame's update sweep the chunks first? Only after a countdown left a sound at life 0. */
    static boolean needsChunkSweep() { return sweepNeeded; }

    /** Records whether the countdown just left a sound at life 0. */
    static void afterCountdown(boolean anyExpired) { sweepNeeded = anyExpired; }

    /**
     * WorldSoundManager.update, re-implemented: the chunk sweep runs only when the last countdown expired a sound (and
     * not on a dedicated server, as stock), then one countdown pass under the sound list's lock. Stock's releases and
     * their order are kept. Returns after the work; the caller skips the stock body.
     */
    static void update() {
        WorldSoundManager m = WorldSoundManager.instance;
        if (!GameServer.server && needsChunkSweep()) {
            int players = IsoPlayer.numPlayers;
            IsoCell cell = IsoWorld.instance.currentCell;
            for (int p = 0; p < players; p++) {
                IsoChunkMap map = cell.chunkMap[p];
                if (map.ignore) continue;
                int width = IsoChunkMap.chunkGridWidth;
                for (int y = 0; y < width; y++) {
                    for (int x = 0; x < width; x++) {
                        IsoChunk c = map.getChunk(x, y);
                        if (c != null) c.updateSounds();
                    }
                }
            }
        }
        boolean expired = false;
        List<WorldSound> list = m.soundList;
        synchronized (list) {
            int keep = 0;
            int n = list.size();
            for (int i = 0; i < n; i++) {
                WorldSound s = list.get(i);
                if (s == null || s.life <= 0) {
                    m.release(s);
                } else {
                    list.set(keep++, s);
                    s.life--;
                    if (s.life <= 0) expired = true;
                }
            }
            list.subList(keep, n).clear();
        }
        afterCountdown(expired);
    }

    /**
     * WorldSoundManager.getBiggestSoundZomb, re-implemented with the loop-invariant parts hoisted: the zombie's hearing
     * multiplier, its square and its room are read once. Stock's constants and early returns are kept.
     */
    static ResultBiggestSound biggestSoundZomb(int x, int y, int z, boolean ignoreZombieSources, IsoZombie zombie) {
        WorldSoundManager m = WorldSoundManager.instance;
        ResultBiggestSound out = result.get();
        IsoChunk chunk = null;
        if (zombie != null) {
            IsoGridSquare zs = zombie.getCurrentSquare();
            if (zs == null) return out.init(null, 0f);
            chunk = zs.chunk;
        }
        List<WorldSound> list = (chunk == null || GameServer.server) ? m.soundList : chunk.soundList;
        WorldSound best = null;
        float max = -1000000f;
        if (!list.isEmpty()) {
            float hearing = m.getHearingMultiplier(zombie);
            IsoCell cell = IsoWorld.instance.currentCell;
            IsoGridSquare zombieSquare = cell.getGridSquare(x, y, z);
            IsoRoom zombieRoom = zombieSquare == null ? null : zombieSquare.getRoom();
            for (int i = 0; i < list.size(); i++) {
                WorldSound s = list.get(i);
                if (s == null || !s.stressZombies || s.radius == 0) continue;
                float dsq = distanceToSquared(x, y, z * 3, s.x, s.y, s.z * 3);
                float rad = (float) s.radius * hearing;
                if (dsq > rad * rad) continue;
                if (dsq < s.zombieIgnoreDist * s.zombieIgnoreDist && z == s.z) continue;
                if (ignoreZombieSources && s.sourceIsZombie) continue;
                IsoGridSquare soundSquare = cell.getGridSquare(s.x, s.y, s.z);
                boolean squaresKnown = soundSquare != null && zombieSquare != null;
                IsoRoom soundRoom = squaresKnown ? soundSquare.getRoom() : null;
                boolean sameRoom = !squaresKnown || soundRoom == zombieRoom;
                boolean oneOutdoors = squaresKnown && (soundRoom == null || zombieRoom == null);
                float att = attenuation(x, y, z, s.x, s.y, s.z, rad, squaresKnown, sameRoom, oneOutdoors);
                if (att <= 0f) continue;
                float attract = (float) s.volume * att;
                if (attract > max) {
                    max = attract;
                    best = s;
                }
            }
        }
        return out.init(best, max);
    }

    /**
     * The per-sound attenuation factor of stock: 1 - (squared distance / rad^2), with z counted three times, and the
     * ratio multiplied by 1.2 for two squares in different rooms and by 1.4 more when one of them is outdoors (no room).
     * Clamped to 1 above; a value at or below 0 means the sound is not heard.
     */
    static float attenuation(int zx, int zy, int zz, int sx, int sy, int sz, float rad, boolean squaresKnown,
                             boolean sameRoom, boolean oneOutdoors) {
        float att = distanceToSquared(zx, zy, zz * 3, sx, sy, sz * 3) / (rad * rad);
        if (squaresKnown && !sameRoom) {
            att *= 1.2f;
            if (oneOutdoors) att *= 1.4f;
        }
        float f = 1f - att;
        return f > 1f ? 1f : f;
    }

    /** Same arithmetic as IsoUtils.DistanceToSquared: float differences, squares summed in double, cast to float. */
    private static float distanceToSquared(float x1, float y1, float z1, float x2, float y2, float z2) {
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        return (float) ((double) dx * dx + (double) dy * dy + (double) dz * dz);
    }

    private static float distanceToSquared(int x1, int y1, int z1, int x2, int y2, int z2) {
        return distanceToSquared((float) x1, (float) y1, (float) z1, (float) x2, (float) y2, (float) z2);
    }

    /**
     * FishSchoolManager.addSoundNoise memo: true when the same (x, y, r) was already seen in this game minute, so the
     * fish walk can be skipped. The memo is cleared when the minute changes.
     */
    static boolean fishSeen(int x, int y, int r, long gameMinute) {
        synchronized (fishMemo) {
            if (gameMinute != fishMinute) {
                fishMemo.clear();
                fishMinute = gameMinute;
            }
            return !fishMemo.add(new FishKey(x, y, r));
        }
    }

    /** The current game minute, as the memo keys it. */
    static long gameMinute() {
        return GameTime.instance.getCalender().getTimeInMillis() / 60000L;
    }
}
