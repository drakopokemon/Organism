package dev.organism.symbiont;

import java.util.ArrayList;

/** Chaos-утилита: аллоцирует до OutOfMemoryError и выходит с кодом 99. Для OOM-injection в chaos-тесте. */
public final class OomHog {
    public static void main(String[] args) {
        var hold = new ArrayList<byte[]>();
        try {
            while (true) hold.add(new byte[8 * 1024 * 1024]);
        } catch (OutOfMemoryError e) {
            System.out.println("OOM_INJECTED chunks=" + hold.size());
            System.exit(99);
        }
    }
}
