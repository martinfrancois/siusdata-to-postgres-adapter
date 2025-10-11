package edu.berkeley.cs.jqf.fuzz;

import edu.berkeley.cs.jqf.fuzz.ei.ZestCLI;

/**
 * Compatibility shim until JQF publishes the zest launcher for version 2.1.
 */
public final class Launch {
    private Launch() {
    }

    public static void main(String[] args) throws Exception {
        ZestCLI.main(args);
    }
}
