# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Attributes that keep release stack traces readable, while still obfuscating
# the original source file name.
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,SourceFile,LineNumberTable,*Annotation*,EnclosingMethod
-renamesourcefileattribute SourceFile

# The app needs no keep rules of its own: it uses no reflection or dynamic class
# loading, Hilt and Navigation ship consumer rules, and AGP generates keep rules
# for the classes named in the manifest and in XML resources (ElevationFragment,
# StabilityLineView). Keep anything added here narrow — a package-wide
# `-keep class com.bizzarosn.heightmark.** { *; }` opts the entire app out of
# minification, which costs ~450 KB in the release APK.
