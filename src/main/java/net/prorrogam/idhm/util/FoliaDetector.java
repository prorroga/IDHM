package net.prorrogam.idhm.util;

public final class FoliaDetector {

    public static final boolean IS_FOLIA = exists("io.papermc.paper.threadedregions.RegionizedServer");

    private FoliaDetector() {
    }

    private static boolean exists(String className) {
        try {
            Class.forName(className, false, FoliaDetector.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
