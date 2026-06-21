# Keep native bridge symbols for debugging in skeleton stage.
-keepclasseswithmembers class ai.mobilecore.runtime.RuntimeBridge {
    public static java.lang.String info();
    public static java.lang.String loadModel(java.lang.String, int);
    public static java.lang.String chat(java.lang.String, java.lang.String, int, float);
    public static void unload();
}
