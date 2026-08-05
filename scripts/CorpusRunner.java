// Round 839: run the whole JUnit4 corpus suite in ONE plain `java` process, so it
// can be launched with and without -XX:AOTCache and the two results compared.
// Gradle's test worker cannot carry an AOT arm (the cache is bound to a classpath
// whose dump-time prefix must match, and the worker's is built by Gradle), so the
// suite is driven directly here.
//
//   java -cp <cp> CorpusRunner <test-classes-dir> <report-file>
//
// Prints one line per test class: NAME run failed ignored, then a TOTAL line.
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

public class CorpusRunner {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        PrintWriter out = new PrintWriter(args[1]);
        String filter = args.length > 2 ? args[2] : null;
        List<String> names = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream.filter(f -> f.toString().endsWith(".class"))::iterator) {
                String rel = root.relativize(p).toString();
                String cn = rel.substring(0, rel.length() - 6).replace('/', '.');
                if (cn.contains("$")) continue;
                if (filter != null && !cn.contains(filter)) continue;
                names.add(cn);
            }
        }
        Collections.sort(names);
        long run = 0, failed = 0, ignored = 0;
        int classes = 0;
        List<String> failures = new ArrayList<>();
        for (String cn : names) {
            Class<?> c;
            try {
                c = Class.forName(cn, false, CorpusRunner.class.getClassLoader());
            } catch (Throwable t) { continue; }
            boolean hasTest = false;
            try {
                for (Method m : c.getMethods()) {
                    if (m.isAnnotationPresent(org.junit.Test.class)) { hasTest = true; break; }
                }
            } catch (Throwable t) { continue; }
            if (!hasTest) continue;
            classes++;
            Result r = JUnitCore.runClasses(c);
            run += r.getRunCount();
            failed += r.getFailureCount();
            ignored += r.getIgnoreCount();
            out.printf("%s %d %d %d%n", cn, r.getRunCount(), r.getFailureCount(), r.getIgnoreCount());
            for (Failure f : r.getFailures()) {
                String head = f.getMessage() == null ? "" : f.getMessage().split("\n")[0];
                failures.add(f.getTestHeader() + " :: " + head);
            }
        }
        out.printf("TOTAL classes=%d run=%d failed=%d ignored=%d%n", classes, run, failed, ignored);
        for (String f : failures) out.printf("FAILURE %s%n", f);
        out.flush();
        out.close();
        System.out.printf("TOTAL classes=%d run=%d failed=%d ignored=%d%n", classes, run, failed, ignored);
    }
}
