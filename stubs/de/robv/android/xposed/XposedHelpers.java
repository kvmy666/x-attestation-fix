package de.robv.android.xposed;

public final class XposedHelpers {
    public static Class<?> findClass(String n, ClassLoader cl) throws ClassNotFoundException { return null; }
    public static Class<?> findClassIfExists(String n, ClassLoader cl) { return null; }
    public static Object callMethod(Object o, String m, Object... args) { return null; }
    public static Object callStaticMethod(Class<?> c, String m, Object... args) { return null; }
    public static Object getObjectField(Object o, String f) { return null; }
    public static void setObjectField(Object o, String f, Object v) {}
    public static Object newInstance(Class<?> c, Object... args) { return null; }
    public static boolean getBooleanField(Object o, String f) { return false; }
    public static void setBooleanField(Object o, String f, boolean v) {}
    // NOTE: these MUST match the real Xposed API return types exactly. The dex records the full
    // method descriptor (including return type); a mismatch (e.g. void vs Object) makes the runtime
    // lookup fail with NoSuchMethodError. All three return the previous value (Object).
    public static Object setAdditionalInstanceField(Object o, String key, Object value) { return null; }
    public static Object getAdditionalInstanceField(Object o, String key) { return null; }
    public static Object removeAdditionalInstanceField(Object o, String key) { return null; }
    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> c, String m, Object... args) { return null; }
    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader cl, String m, Object... args) { return null; }
}