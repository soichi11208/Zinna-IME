package com.google.android.apps.inputmethod.libs.mozc.session;

/**
 * JNI shim for libmozc.so.
 *
 * <p>The fully-qualified name of this class is NOT a style choice. mozc's {@code
 * android/jni/mozcjni.cc} exports exactly one symbol,
 * {@code Java_com_google_android_apps_inputmethod_libs_mozc_session_MozcJNI_initialize}, and that
 * function registers the remaining natives via {@code RegisterNatives} on whatever class object JNI
 * handed it. So the class must live at this exact package/name, and the four methods below must
 * match the signatures in the {@code JNINativeMethod} table in mozcjni.cc:
 *
 * <pre>
 *   evalCommand      ([B)[B
 *   onPostLoad       (Ljava/lang/String;Ljava/lang/String;)Z
 *   setEncryptionKey ([B)Z
 *   getDataVersion   ()Ljava/lang/String;
 * </pre>
 *
 * {@code setEncryptionKey} is not upstream; it comes from
 * {@code patches/0002-android-keystore-profile-encryption.patch}, so an unpatched libmozc.so will
 * throw {@link UnsatisfiedLinkError} on that one method and register the other three normally.
 *
 * <p>This class is deliberately package-visible plumbing only. Application code should go through
 * {@code io.github.soichi11208.zinna.mozc.MozcEngine}, which owns the lifecycle and threading rules.
 */
public final class MozcJNI {

    private MozcJNI() {}

    /**
     * Registers the other natives. Must be called after {@code System.loadLibrary("mozc")} and
     * before anything else here.
     */
    public static native boolean initialize();

    /**
     * Supplies the key for mozc's encrypted storage. Must be called before {@link #onPostLoad}.
     *
     * @param key exactly 32 bytes; see {@code io.github.soichi11208.zinna.mozc.MozcProfileKey}
     * @return false if the native side rejected it
     */
    public static native boolean setEncryptionKey(byte[] key);

    /** Serialized {@code mozc.commands.Command} in, serialized {@code Command} out. */
    public static native byte[] evalCommand(byte[] command);

    /**
     * Creates the global SessionHandler.
     *
     * @param userProfileDirectoryPath writable dir for the user dictionary, history, config
     * @param dataFilePath absolute path to mozc.data
     */
    public static native boolean onPostLoad(String userProfileDirectoryPath, String dataFilePath);

    /** Version string of the loaded mozc.data, or "" if the handler is not up. */
    public static native String getDataVersion();
}
