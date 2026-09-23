# R8 rules for LightChat. Full mode is on (see gradle.properties), which changes two things
# worth remembering while reading this file: a `-keep` on a class no longer implies keeping its
# members, and R8 assumes a class it never sees allocated is never instantiated.
#
# Every rule below names the mechanism that reaches the thing being kept. There is deliberately
# no blanket `-keep class com.gios.lightchat.**` — that would keep the whole app and make the
# shrink pointless. light-common contributes its own consumer rules (the LightSyncBackup
# subclass and its constructor, the report queue's enums), so nothing here repeats them.

# ---------------------------------------------------------------- crash reports

# Shake-to-report and the crash handler post a stack trace into a GitHub issue. Without these
# the trace is a wall of `a.a.a` with no line numbers, which is the difference between a report
# that can be fixed and one that can only be acknowledged. light-common asks for the same
# attributes; repeated here because this is the app that reads them.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------- persisted enum names

# MessageStore writes each tapback as `r.type.name` into the message JSON it stores in SQLite,
# and reads it back with `ReactionType.entries.firstOrNull { it.name == ... }`. The constant
# names are therefore *data on disk*, written by one build and read by the next. Full mode is
# free both to rename enum fields and to unbox an enum whose identity it believes is unused;
# either turns every cached tapback into "no match" — silently, and only for rows written before
# the upgrade. `<fields>` is the load-bearing line: keeping the class alone would not keep them.
-keepclassmembers enum com.gios.lightchat.ReactionType {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------- BlueBubbles JSON

# Nothing to keep, and that is worth stating rather than leaving to be rediscovered. The
# BlueBubbles REST payloads and the Socket.IO event payloads are parsed field by field with
# org.json in BlueBubblesApi — `obj.optString("guid")` and so on — so no model class is ever
# constructed or populated by reflection, and every field name is a string literal that R8
# cannot touch. There is no Gson, no Moshi and no kotlinx-serialization in this app. If one is
# ever added, its rules belong here and the models it reads will need @Keep or an explicit
# -keepclassmembers, because full mode will otherwise rename exactly the fields it matches on.
#
# Socket.IO event names ("new-message", "typing-indicator", ...) are likewise string literals
# passed to `Socket.on`; the listeners are anonymous Emitter.Listener classes reached by a
# normal call, so they need no rule either.

# ---------------------------------------------------------------- Socket.IO / OkHttp

# engine.io-client pulls OkHttp 3.12, which predates OkHttp shipping usable consumer rules for
# R8 and references TLS providers it looks up with Class.forName at runtime. Missing-class
# errors from those optional providers fail the build in full mode; none of them is present on
# the Light Phone III, which uses the platform provider.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn javax.annotation.**

# OkHttp loads the public-suffix list by class name to find its bundled resource. Names, not
# members: the class only has to keep its name for the resource lookup to resolve.
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ---------------------------------------------------------------- WorkManager

# WorkManager's default factory instantiates a worker by reflecting on the class name it stored
# in its own database when the work was enqueued — so a worker enqueued by yesterday's build is
# looked up by yesterday's name after an update, and nothing in the code refers to the class in
# a way R8 can see once the name changes. The two-argument constructor is spelled out because a
# `-keep` on the class does not keep it in full mode, and its absence is a
# `ClassNotFoundException` inside WorkManager rather than anywhere near here.
-keep class com.gios.lightchat.DeliveryWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---------------------------------------------------------------- manifest-named components

# Every Service, BroadcastReceiver, Activity and ContentProvider named as a string in the
# manifest is reached only by that string. AGP generates keep rules for them from the merged
# manifest, so this section normally needs nothing — but the generated rules keep the *class*,
# and full mode does not extend that to the no-argument constructor the framework calls. Naming
# them here is cheap and removes the whole class of "works in compat mode, ClassNotFound in full
# mode" failures, which on a receiver only show up hours later when an alarm fires.
-keep class com.gios.lightchat.PollAlarm { public <init>(); }
-keep class com.gios.lightchat.socket.SocketService { public <init>(); }
-keep class com.gios.lightchat.socket.BootReceiver { public <init>(); }
-keep class com.gios.lightchat.socket.PackageReplacedReceiver { public <init>(); }
-keep class com.gios.lightchat.share.ChatsProvider { public <init>(); }
-keep class com.gios.lightchat.share.CodeProvider { public <init>(); }
-keep class com.gios.lightchat.backup.Backup { public <init>(); }

# ---------------------------------------------------------------- Beeper / Trixnity

# Trixnity's olm driver reaches libolm through JNA, which looks fields up by name from native
# code. Renamed or stripped, E2EE fails at login with "Can't obtain peer field ID for class
# com.sun.jna.Pointer". Same rules fenleon/chats ships on the LP3.
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-keep class de.connect2x.trixnity.libolm.** { *; }

# The whole Matrix stack is kept rather than tuned. It builds its services through koin and
# its events through kotlinx-serialization polymorphism, and a class R8 wrongly believes unused
# fails only at runtime, only in a release build, only in an encrypted room. The size cost is a
# few MB on an app that is already mostly Compose.
-keep class de.connect2x.trixnity.** { *; }
-keep class de.connect2x.lognity.** { *; }
-dontwarn de.connect2x.**
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn org.koin.**
