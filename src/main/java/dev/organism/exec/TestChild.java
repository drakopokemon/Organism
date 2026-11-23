package dev.organism.exec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Тестовый ребёнок для SupervisorSelfTest: exit0 | fail2 <counterFile> | sleep (graceful drain через shutdown hook). */
public final class TestChild {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "exit0";
        System.out.println("EPOCH=" + System.getenv().getOrDefault("ORGANISM_EPOCH", "?"));
        switch (mode) {
            case "exit0" -> System.exit(0);
            case "fail2" -> {
                Path f = Path.of(args[1]);
                long n = Files.exists(f) ? Long.parseLong(Files.readString(f).trim()) : 0;
                n++;
                Files.writeString(f, Long.toString(n));
                System.exit(n < 3 ? 7 : 0);
            }
            case "sleep" -> {
                AtomicBoolean stop = new AtomicBoolean(false);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    stop.set(true);
                    System.out.println("DRAINED");
                    System.out.flush();
                }));
                System.out.println("READY");
                System.out.flush();
                while (!stop.get()) LockSupport.parkNanos(20_000_000L);
            }
            default -> System.exit(2);
        }
    }
}
