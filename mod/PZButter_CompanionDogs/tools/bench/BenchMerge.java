import java.util.Arrays;
import java.util.HashMap;
import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.stdlib.BaseLib;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;

/**
 * Cost per tick of 27 trivial Lua handlers: one engine call each (what Events.X.Add gives)
 * against one merged Lua listener (pcall each, direct calls, direct calls with a fault guard).
 * No game needed. From the mod folder:
 *   PZ=<game>/projectzomboid; javac -proc:none -cp $PZ/projectzomboid.jar -d /tmp/bm tools/bench/BenchMerge.java
 *   java -cp "/tmp/bm:$PZ/projectzomboid.jar:$PZ/*" BenchMerge
 * 42.21, 2026-11-10: separate 5491, merged+pcall 12106, merged direct 8219, merged guard 8981 ns/tick.
 */
public class BenchMerge {
    public static void main(String[] a) throws Exception {
        KahluaTableImpl env = new KahluaTableImpl(new HashMap<>());
        BaseLib.register(env);
        KahluaThread kt = new KahluaThread(J2SEPlatform.getInstance(), env);
        java.lang.reflect.Field f = KahluaThread.class.getDeclaredField("debugOwnerThread");
        f.setAccessible(true); f.set(kt, Thread.currentThread());
        LuaCaller caller = new LuaCaller(new KahluaConverterManager());
        String src =
            "cnt = 0\n" +
            "fns = {}\n" +
            "for i = 1, 27 do fns[i] = function(x) cnt = cnt + 1 end end\n" +
            "local n = 27\n" +
            "function merged_pcall(x) for i = 1, n do local ok, err = pcall(fns[i], x) if not ok then cnt = -1 end end end\n" +
            "function merged_direct(x) for i = 1, n do fns[i](x) end end\n" +
            "local cur = 0\n" +
            "function merged_guard(x) if cur ~= 0 then cnt = -1 end for i = 1, n do cur = i fns[i](x) end cur = 0 end\n";
        caller.pcall(kt, LuaCompiler.loadstring(src, "t", env), new Object[0]);
        KahluaTable fns = (KahluaTable) env.rawget("fns");
        Object[] closures = new Object[27];
        for (int i = 0; i < 27; i++) closures[i] = fns.rawget(i + 1);
        Object mp = env.rawget("merged_pcall"), md = env.rawget("merged_direct"), mg = env.rawget("merged_guard");
        Object[] args = { Double.valueOf(1) };
        int ticks = 200_000;
        String[] names = { "separate(27 engine calls)", "merged+pcall", "merged direct", "merged guard" };
        double[][] res = new double[4][7];
        for (int round = -3; round < 7; round++) {
            for (int s = 0; s < 4; s++) {
                long t0 = System.nanoTime();
                for (int t = 0; t < ticks; t++) {
                    if (s == 0) for (int i = 0; i < 27; i++) caller.protectedCallVoid(kt, closures[i], args);
                    else caller.protectedCallVoid(kt, s == 1 ? mp : s == 2 ? md : mg, args);
                }
                double ns = (System.nanoTime() - t0) / (double) ticks;
                if (round >= 0) res[s][round] = ns;
            }
        }
        for (int s = 0; s < 4; s++) { Arrays.sort(res[s]); System.out.printf("%-28s median %.0f ns/tick%n", names[s], res[s][3]); }
        System.out.println("cnt=" + env.rawget("cnt"));
    }
}
