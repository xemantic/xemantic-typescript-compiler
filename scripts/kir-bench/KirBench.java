import com.xemantic.typescript.compiler.kir.KirCompilation;
import com.xemantic.typescript.compiler.kir.TypeScriptToKotlinIrKt;
import com.xemantic.typescript.compiler.kir.emit.GeneratedProgramClasspath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Scratch harness: compiles a TypeScript project to JVM classes through the KIR
 * backend and leaves them on disk, so the benchmark can then run the generated
 * program as an ordinary `java` process — repeatedly, and with nothing of the
 * compiler in the picture.
 */
public final class KirBench {

    public static void main(String[] args) throws Exception {
        String project = args[0];
        String entry = args[1];
        Path out = Paths.get(args[2]);
        Files.createDirectories(out);
        List<Path> cp = GeneratedProgramClasspath.INSTANCE.minimal();
        long t0 = System.nanoTime();
        KirCompilation c = TypeScriptToKotlinIrKt
            .compileTypeScriptProjectToJvm(project, entry, out, "program", cp);
        long ms = (System.nanoTime() - t0) / 1000000L;
        System.out.println("KIR_COMPILE_MS=" + ms);
        System.out.println("KIR_SUCCESS=" + c.getSuccessful());
        System.out.println("KIR_MAIN=" + c.getMainClass());
        StringBuilder sb = new StringBuilder(out.toString());
        for (Path p : cp) sb.append(':').append(p);
        System.out.println("KIR_RUN_CLASSPATH=" + sb);
        if (!c.getSuccessful()) System.out.println(c);
    }

}
