package pzbutter;

import zombie.characters.IsoZombie;
import zombie.ai.states.ZombieIdleState;
import zombie.ai.states.WalkTowardState;
import zombie.ai.states.PathFindState;
import zombie.ai.states.ZombieSittingState;
import zombie.characters.IsoPlayer;

/** Calm: whether a zombie's update is safe to count as calm. */
final class Calm {
    private Calm() {}

    record Facts(boolean zombie, boolean dead, boolean fakeDead, boolean stateOk, boolean hasTarget,
                 boolean inVehicle, boolean onFire, boolean ragdoll, boolean thumping, boolean grappling,
                 boolean grappled, boolean onFloor, boolean knockedDown, boolean crawling, boolean attacking,
                 float nearestPlayerDist) {}

    static boolean test(Facts f) {
        if (!f.zombie()) return false;
        if (f.dead() || f.fakeDead() || f.hasTarget() || f.inVehicle() || f.onFire() || f.ragdoll()
                || f.thumping() || f.grappling() || f.grappled() || f.onFloor() || f.knockedDown()
                || f.crawling() || f.attacking()) return false;
        if (!f.stateOk()) return false;
        return f.nearestPlayerDist() > 12.0f;
    }

    static Facts of(Object movingObject) {
        if (!(movingObject instanceof IsoZombie z)) {
            return new Facts(false, false, false, false, false, false, false, false, false, false,
                    false, false, false, false, false, Float.MAX_VALUE);
        }
        boolean stateOk;
        try {
            Object s = z.getCurrentState();
            stateOk = s == ZombieIdleState.instance() || s == WalkTowardState.instance()
                    || s == PathFindState.instance() || s == ZombieSittingState.instance();
        } catch (Throwable t) { stateOk = false; }
        boolean hasTarget, inVehicle, onFire, ragdoll, thumping, grappling, grappled, dead, fakeDead, onFloor, knockedDown, crawling, attacking;
        try { hasTarget = z.getTarget() != null; } catch (Throwable t) { hasTarget = false; }
        try { inVehicle = z.getVehicle() != null; } catch (Throwable t) { inVehicle = false; }
        try { onFire = z.isOnFire(); } catch (Throwable t) { onFire = false; }
        try { ragdoll = z.isRagdoll(); } catch (Throwable t) { ragdoll = false; }
        try { thumping = z.getThumpTarget() != null; } catch (Throwable t) { thumping = false; }
        // grappling via getGrappleable
        boolean gr = false, grl = false;
        try {
            Object g = z.getGrappleable();
            if (g != null) {
                try { gr = ((Boolean) g.getClass().getMethod("isGrappling").invoke(g)); } catch (Throwable t) { gr = false; }
                try { grl = ((Boolean) g.getClass().getMethod("isBeingGrappled").invoke(g)); } catch (Throwable t) { grl = false; }
            }
        } catch (Throwable t) { gr = false; grl = false; }
        grappling = gr; grappled = grl;
        try { dead = z.isDead(); } catch (Throwable t) { dead = false; }
        try { fakeDead = z.isFakeDead(); } catch (Throwable t) { fakeDead = false; }
        try { onFloor = z.isOnFloor(); } catch (Throwable t) { onFloor = false; }
        try { knockedDown = z.isKnockedDown(); } catch (Throwable t) { knockedDown = false; }
        try { crawling = z.isCrawling(); } catch (Throwable t) { crawling = false; }
        try { attacking = z.isAttacking(); } catch (Throwable t) { attacking = false; }
        float nearest = Float.MAX_VALUE;
        try {
            zombie.characters.IsoPlayer[] ps = IsoPlayer.players;
            int n = IsoPlayer.numPlayers;
            for (int i = 0; i < n; i++) {
                IsoPlayer p = (ps == null || i >= ps.length) ? null : ps[i];
                if (p == null) continue;
                float d = z.DistTo(p);
                if (d < nearest) nearest = d;
            }
        } catch (Throwable t) { nearest = Float.MAX_VALUE; }
        return new Facts(true, dead, fakeDead, stateOk, hasTarget, inVehicle, onFire, ragdoll, thumping,
                grappling, grappled, onFloor, knockedDown, crawling, attacking, nearest);
    }
}