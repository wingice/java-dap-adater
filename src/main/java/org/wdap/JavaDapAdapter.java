package org.wdap;

import java.io.InputStream;
import java.io.OutputStream;

import com.microsoft.java.debug.core.adapter.IProviderContext;
import com.microsoft.java.debug.core.adapter.ProtocolServer;
import com.microsoft.java.debug.core.adapter.ProviderContext;

/**
 * Standalone Java DAP (Debug Adapter Protocol) server.
 * 
 * Communicates via stdin/stdout using the DAP wire protocol.
 * Attaches to a running JVM via JDWP and provides:
 *   - Breakpoints (line, method, conditional)
 *   - Stack frames, variables, threads
 *   - Expression evaluation (via JDI invoke)
 *   - Step in/out/over
 *
 * Usage:
 *   java -jar java-dap-adapter.jar [--source-roots <path1>;<path2>;...]
 *
 * The DAP client sends an "attach" request with:
 *   { "hostName": "localhost", "port": 5005 }
 *
 * Designed for AI-agent integration with the Oh My Pi harness debug tool.
 */
public class JavaDapAdapter {

    public static void main(String[] args) {
        String[] sourceRoots = parseSourceRoots(args);

        InputStream in = System.in;
        OutputStream out = System.out;

        // Redirect System.out to System.err so our logging doesn't corrupt DAP protocol
        System.setOut(System.err);

        IProviderContext context = createProviderContext(sourceRoots);
        ProtocolServer server = new ProtocolServer(in, out, context);

        // Block until the debug session ends
        server.run();
    }

    private static IProviderContext createProviderContext(String[] sourceRoots) {
        ProviderContext context = new ProviderContext();
        context.registerProvider(
            com.microsoft.java.debug.core.adapter.ISourceLookUpProvider.class,
            new FileSystemSourceLookUpProvider(sourceRoots)
        );
        context.registerProvider(
            com.microsoft.java.debug.core.adapter.IEvaluationProvider.class,
            new JdiEvaluationProvider()
        );
        context.registerProvider(
            com.microsoft.java.debug.core.adapter.IHotCodeReplaceProvider.class,
            new NoOpHotCodeReplaceProvider()
        );
        context.registerProvider(
            com.microsoft.java.debug.core.adapter.ICompletionsProvider.class,
            new NoOpCompletionsProvider()
        );
        context.registerProvider(
            com.microsoft.java.debug.core.adapter.IVirtualMachineManagerProvider.class,
            new DefaultVirtualMachineManagerProvider()
        );
        return context;
    }

    private static String[] parseSourceRoots(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--source-roots".equals(args[i])) {
                return args[i + 1].split(";");
            }
        }
        // Default: current directory
        return new String[]{"."};
    }
}
