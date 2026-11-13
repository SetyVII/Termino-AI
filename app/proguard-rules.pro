# Término release rules

# Room
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Kotlin metadata used by reflection in Compose / Room.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
