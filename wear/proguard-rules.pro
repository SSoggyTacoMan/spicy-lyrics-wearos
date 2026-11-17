# The watch app ships minified; AndroidX and Play services bring their own rules. What R8 can't
# see is the Data Layer's callbacks, which are invoked by name from the Wearable listener service
# and the tile and complication providers (all named in the manifest, so they're kept).

# Protolayout's node types are built by builders but read back by R8-invisible reflection in the
# tile renderer.
-keep class androidx.wear.protolayout.** { *; }
-dontwarn androidx.wear.protolayout.**

# The Data Layer delivers these interfaces through the Play services process.
-keep interface com.google.android.gms.wearable.** { *; }
