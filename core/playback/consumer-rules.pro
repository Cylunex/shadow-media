# libplayer.so resolves both classes and these method names directly through JNI.
-keep class is.xyz.mpv.MPVLib { *; }
-keep class com.fongmi.android.tv.player.iso.IsoSessionManager { public static *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
