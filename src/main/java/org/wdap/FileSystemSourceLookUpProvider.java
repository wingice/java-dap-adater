package org.wdap;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.microsoft.java.debug.core.DebugException;
import com.microsoft.java.debug.core.JavaBreakpointLocation;
import com.microsoft.java.debug.core.adapter.ISourceLookUpProvider;
import com.microsoft.java.debug.core.protocol.Types.SourceBreakpoint;

/**
 * Resolves source files from the filesystem by scanning source roots.
 * Maps fully-qualified class names to .java file paths.
 */
public class FileSystemSourceLookUpProvider implements ISourceLookUpProvider {

    private final String[] sourceRoots;
    private final Map<String, Path> classToSource = new ConcurrentHashMap<>();
    private volatile boolean indexed = false;

    public FileSystemSourceLookUpProvider(String[] sourceRoots) {
        this.sourceRoots = sourceRoots;
    }

    @Override
    public boolean supportsRealtimeBreakpointVerification() {
        return false;
    }

    @Override
    public String[] getFullyQualifiedName(String uri, int[] lines, int[] columns) throws DebugException {
        // Convert source file URI to fully-qualified class name
        // e.g., file:///path/to/src/main/java/com/example/Foo.java → com.example.Foo
        String path = uriToPath(uri);
        String[] result = new String[lines.length];
        String fqn = pathToFqn(path);
        Arrays.fill(result, fqn);
        return result;
    }

    @Override
    public JavaBreakpointLocation[] getBreakpointLocations(String sourceUri, SourceBreakpoint[] sourceBreakpoints) throws DebugException {
        String path = uriToPath(sourceUri);
        String fqn = pathToFqn(path);
        JavaBreakpointLocation[] locations = new JavaBreakpointLocation[sourceBreakpoints.length];
        for (int i = 0; i < sourceBreakpoints.length; i++) {
            locations[i] = new JavaBreakpointLocation(sourceBreakpoints[i].line, sourceBreakpoints[i].column);
            locations[i].setClassName(fqn);
        }
        return locations;
    }

    @Override
    public String getSourceFileURI(String fullyQualifiedName, String sourcePath) {
        ensureIndexed();
        // Try direct lookup
        Path source = classToSource.get(fullyQualifiedName);
        if (source != null) {
            return source.toUri().toString();
        }
        // Fallback: construct path from FQN
        String relativePath = fullyQualifiedName.replace('.', '/') + ".java";
        for (String root : sourceRoots) {
            Path candidate = Paths.get(root, relativePath);
            if (Files.exists(candidate)) {
                return candidate.toUri().toString();
            }
        }
        // Try sourcePath directly
        if (sourcePath != null) {
            for (String root : sourceRoots) {
                Path candidate = Paths.get(root, sourcePath);
                if (Files.exists(candidate)) {
                    return candidate.toUri().toString();
                }
            }
        }
        return null;
    }

    @Override
    public String getSourceContents(String uri) {
        try {
            Path path = Paths.get(uriToPath(uri));
            if (Files.exists(path)) {
                return Files.readString(path);
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    @Override
    public List<MethodInvocation> findMethodInvocations(String uri, int line) {
        return Collections.emptyList();
    }

    private void ensureIndexed() {
        if (indexed) return;
        synchronized (this) {
            if (indexed) return;
            for (String root : sourceRoots) {
                Path rootPath = Paths.get(root);
                if (!Files.isDirectory(rootPath)) continue;
                try {
                    Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (file.toString().endsWith(".java")) {
                                String fqn = pathToFqn(file.toString());
                                if (fqn != null) {
                                    classToSource.put(fqn, file);
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            String name = dir.getFileName().toString();
                            // Skip common non-source directories
                            if (name.equals("target") || name.equals("node_modules") || name.equals(".git")) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (IOException e) {
                    System.err.println("Warning: Failed to index " + root + ": " + e.getMessage());
                }
            }
            indexed = true;
        }
    }

    private String uriToPath(String uri) {
        if (uri.startsWith("file:///")) {
            return uri.substring("file:///".length()).replace('/', '\\');
        } else if (uri.startsWith("file://")) {
            return uri.substring("file://".length()).replace('/', '\\');
        }
        return uri;
    }

    private String pathToFqn(String filePath) {
        // Always look for src/main/java or src/test/java as the package root
        String normalized = filePath.replace('\\', '/');
        String[] markers = {"/src/main/java/", "/src/test/java/"};
        for (String marker : markers) {
            int idx = normalized.indexOf(marker);
            if (idx >= 0) {
                String relative = normalized.substring(idx + marker.length());
                return relative.replace('/', '.').replace(".java", "");
            }
        }
        return null;
    }
}
