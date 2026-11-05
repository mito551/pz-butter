package cd.perf;

import java.util.List;

import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Captures the zombies a watched animal is told about during its updateLOS, for CD_PerfNear's
 * perception fast path.
 *
 * ZombieBuddy inlines advice bodies into the patched game methods, so an advice may only touch
 * public members of public classes: each advice below is a single call to a public static method
 * here, and the state stays private. Failures are swallowed so the game is never affected.
 */
public final class Spotted {
    private Spotted() {}

    /** Zombies kept per animal per updateLOS; Lua treats a full record as possibly truncated. */
    public static final int CAP = SpotBook.CAP;
    private static final double RANGE = 40.0;

    private static final SpotBook<Object, Object> BOOK = new SpotBook<>();
    private static final ThreadLocal<IsoAnimal> CURRENT = new ThreadLocal<>();

    @LuaMethod(name = "cdWatch", global = true)
    public static void cdWatch(Object animal, boolean on) {
        try { BOOK.watch(animal, on); } catch (Throwable ignored) { }
    }

    /** Array of zombies from the animal's last updateLOS plus field ageMs; nil when none recorded. */
    @LuaMethod(name = "cdSpottedZombies", global = true)
    public static KahluaTable cdSpottedZombies(Object animal) {
        try {
            List<Object> list = BOOK.get(animal);
            if (list == null) return null;
            KahluaTable out = LuaManager.platform.newTable();
            for (int i = 0; i < list.size(); i++) out.rawset(i + 1, list.get(i));
            out.rawset("ageMs", (double) (System.currentTimeMillis() - BOOK.stamp(animal)));
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** updateLOS entry: starts a new record, only for watched animals. */
    public static void begin(Object self) {
        try {
            if (self instanceof IsoAnimal animal && BOOK.begin(animal, System.currentTimeMillis())) {
                CURRENT.set(animal);
            } else {
                CURRENT.remove();
            }
        } catch (Throwable ignored) { }
    }

    /** updateLOS exit, normal or not. */
    public static void end() {
        try { CURRENT.remove(); } catch (Throwable ignored) { }
    }

    /** BaseAnimalBehavior.spotted: a zombie within RANGE tiles of the current watched animal. */
    public static void saw(Object o) {
        try {
            IsoAnimal current = CURRENT.get();
            if (current != null && o instanceof IsoZombie zombie
                    && Math.abs(current.getX() - zombie.getX()) <= RANGE
                    && Math.abs(current.getY() - zombie.getY()) <= RANGE) {
                BOOK.add(current, zombie);
            }
        } catch (Throwable ignored) { }
    }

    @Patch(className = "zombie.characters.animals.IsoAnimal", methodName = "updateLOS")
    public static class UpdateLOS {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self) {
            Spotted.begin(self);
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() {
            Spotted.end();
        }
    }

    @Patch(className = "zombie.characters.animals.behavior.BaseAnimalBehavior", methodName = "spotted")
    public static class SpottedMethod {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) Object o) {
            Spotted.saw(o);
        }
    }
}
