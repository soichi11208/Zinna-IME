# The input method, the settings screens and the layout data survive on their own: the service and
# activities are named in the manifest, and kotlinx.serialization ships its own R8 rules inside
# kotlinx-serialization-core. What R8 cannot work out for itself is anything the *native* side
# reaches for, and those rules live with the code that owns them — see mozc/consumer-rules.pro,
# which keeps MozcJNI under its exact name because mozcjni.cc calls RegisterNatives on that string.

# Line numbers make a crash report from a release build worth reading. The source file name itself
# is noise once the numbers are there.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
