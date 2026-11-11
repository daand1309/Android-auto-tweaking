# Keep all of our own code intact. It's small (~3k lines); the real shrink win is the
# bundled libraries (androidx.car.app, lifecycle, core-ktx). Keeping our package avoids
# any reflection/entry-point surprises and keeps the instrumented tests working on the
# minified release build.
-keep class com.daand.drivemusic.** { *; }

# Android Auto host validator reads this resource array by name.
-keepclassmembers class androidx.car.app.R$array { *; }
