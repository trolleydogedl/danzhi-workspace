import javax.tools.*;
import com.sun.source.util.JavacTask;
import java.nio.file.*;
import java.util.*;

/** Syntax parsing only: no Android SDK symbol resolution or DEX generation is claimed. */
public final class ParseJava {
    public static void main(String[] args)throws Exception{
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();DiagnosticCollector<JavaFileObject> d=new DiagnosticCollector<>();
        List<java.io.File> files=new ArrayList<>();try(java.util.stream.Stream<Path> walk=Files.walk(Paths.get(args[0]))){walk.filter(p->p.toString().endsWith(".java")).forEach(p->files.add(p.toFile()));}
        StandardJavaFileManager fm=compiler.getStandardFileManager(d,null,null);
        JavacTask task=(JavacTask)compiler.getTask(null,fm,d,Arrays.asList("-proc:none","-source","8","-Xlint:-options"),null,fm.getJavaFileObjectsFromFiles(files));
        task.parse();int errors=0;for(Diagnostic<?> x:d.getDiagnostics())if(x.getKind()==Diagnostic.Kind.ERROR){System.out.println(x);errors++;}
        System.out.println("Java syntax-only: "+files.size()+" files, "+errors+" errors (NOT Android compilation)");fm.close();if(errors>0)System.exit(1);
    }
}
