# app_process names this class on the command line.
-keep class com.strike.daemon.CameraDaemon {
    public static void main(java.lang.String[]);
}

-keep class com.strike.online.ParkedAccess {
    public static void main(java.lang.String[]);
}

# libstrike.so looks these up by their fully qualified names.
-keepclasseswithmembernames,includedescriptorclasses class com.strike.camera.CameraTexture {
    native <methods>;
}
