import java.nio.file.*;
import java.util.Arrays;
import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;

/** Per-call cost of PZButter_KI5's dispatcher under the game's Kahlua. Args: Ki5.lua ki5table.lua */
public class BenchKi5 {
    public static void main(String[] a) throws Exception {
        KahluaTable env = J2SEPlatform.getInstance().newEnvironment();
        KahluaThread kt = new KahluaThread(J2SEPlatform.getInstance(), env);
        java.lang.reflect.Field f = KahluaThread.class.getDeclaredField("debugOwnerThread");
        f.setAccessible(true); f.set(kt, Thread.currentThread());
        LuaCaller caller = new LuaCaller(new KahluaConverterManager());
        String pre =
            "local clock = 0\n" +
            "function getTimestampMs() clock = clock + 16 return clock end\n" +
            "function isServer() return false end\n" +
            "calls = 0\n" +
            "local function handler(p) calls = calls + 1 end\n" +
            "PZButter = { Census = { finish = function() end, list = function() return {} end,\n" +
            "    route = function() return handler end } }\n" +
            "local start\n" +
            "Events = { OnGameStart = { Add = function(fn) start = fn end }, OnPlayerUpdate = { Add = function() end } }\n" +
            "function benchInstall()\n" +
            "    local seen, rows = {}, {}\n" +
            "    for _, r in ipairs(PZButter.Ki5Table) do\n" +
            "        if r.kind ~= 'unchecked' and not seen[r.name] then seen[r.name] = true rows[#rows + 1] = r end\n" +
            "    end\n" +
            "    PZButter.Ki5Table = rows\n" +
            "    start()\n" +
            "    return #rows\n" +
            "end\n" +
            "local function veh(name) return { getScriptName = function() return name end, getSeat = function() return 0 end } end\n" +
            "local function player(v) return { getVehicle = function() return v end, getPlayerNum = function() return 0 end } end\n" +
            "onFoot = player(nil)\n" +
            "inVanilla = player(veh('Base.CarNormal'))\n" +
            "inKi5 = player(veh('Base.82firebirdKITT'))\n" +
            "function benchRun(p, n) local d = PZButter.Ki5.dispatch for i = 1, n do d(p) end end\n";
        caller.pcall(kt, LuaCompiler.loadstring(pre, "pre", env), new Object[0]);
        for (int i = 0; i < 2; i++) {
            Object[] r = caller.pcall(kt, LuaCompiler.loadstring(Files.readString(Path.of(a[i])), a[i], env), new Object[0]);
            if (!Boolean.TRUE.equals(r[0])) throw new RuntimeException(Arrays.toString(r));
        }
        Object[] r = caller.pcall(kt, env.rawget("benchInstall"), new Object[0]);
        System.out.println("handlers installed from rows: " + Arrays.toString(r));
        Object run = env.rawget("benchRun");
        int n = a.length > 2 ? Integer.parseInt(a[2]) : 50_000;
        for (String who : new String[] { "onFoot", "inVanilla", "inKi5" }) {
            double[] ns = new double[7];
            for (int round = -3; round < 7; round++) {
                long t0 = System.nanoTime();
                Object[] rr = caller.pcall(kt, run, new Object[] { env.rawget(who), Double.valueOf(n) });
                if (!Boolean.TRUE.equals(rr[0])) throw new RuntimeException(Arrays.toString(rr));
                if (round >= 0) ns[round] = (System.nanoTime() - t0) / (double) n;
            }
            Arrays.sort(ns);
            System.out.printf("%-10s median %.0f ns/dispatch%n", who, ns[3]);
        }
        System.out.println("handler calls=" + env.rawget("calls"));
    }
}
