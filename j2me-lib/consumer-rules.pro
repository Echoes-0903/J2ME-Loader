# MIDlet classes are loaded from a converted dex file by name.
-keep class javax.microedition.** { *; }
-keep class com.nokia.** { *; }
-keep class com.siemens.** { *; }
-keep class com.motorola.** { *; }

# J2meConfig discovers upstream profile fields and serialized keys at runtime.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class ru.playsoftware.j2meloader.config.ProfileModel {
    @com.google.gson.annotations.SerializedName <fields>;
}
