# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/jeff/Development/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}


# > Task :app:minifyFlavorAtestReleaseWithR8 FAILED
# ERROR: Missing classes detected while running R8. Please add the missing classes or apply additional keep rules that are generated in /home/runner/work/markor/markor/app/build/outputs/mapping/flavorAtestRelease/missing_rules.txt.
# ERROR: R8: Missing class java.awt.AlphaComposite (referenced from: java.awt.image.BufferedImage com.vladsch.flexmark.util.ImageUtils.makeRoundedCorner(java.awt.image.BufferedImage, int, int) and 1 other context)
-ignorewarnings

# Git tab (fork feature): GitRepoConfig is (de)serialized by Gson through its fields.
# @SerializedName pins the JSON names, keeping the members keeps reflection working.
-keepclassmembers class net.gsantner.markor.git.GitRepoConfig { <fields>; }
-keepclassmembers enum net.gsantner.markor.git.GitRepoConfig$PullStrategy { *; }

# ---- Git tab: JGit 5.13 (doc/adr/0001-jgit-on-android.md)
# JGit loads its error/progress messages by reflection: TranslationBundle.load() iterates getClass().getFields() of
# JGitText (829 public String fields) and looks each field NAME up in JGitText.properties. Nothing broke without this
# rule in AGP 8.13.2 (R8 recognised the getFields() call and kept all fields by name, see seeds.txt; the flavorAtest
# spike screen passed clone/fetch/pull/push and the auth-failure message path in the release build). The rule pins
# that behaviour so a future R8 cannot silently rename the fields, which would turn every JGit error message into a
# TranslationStringMissingException.
-keepclassmembers class * extends org.eclipse.jgit.nls.TranslationBundle { public <fields>; }
# Missing-class warnings from JGit that -ignorewarnings (above, for flexmark's java.awt) already hides and that are
# genuinely absent on Android: java.lang.management/javax.management (JMX in Monitoring, WindowCache, GC$PidLock) and
# org.ietf.jgss (Kerberos HTTP auth). No keep rule can help; the service layer must avoid those paths (gc.auto=0).

# ---- Git tab: SSH over JSch (doc/adr/0002-ssh-on-android.md)
# Every cipher, MAC, key exchange, signature, hash, random and key-pair generator JSch uses is named
# as a *string* in JSch's config map and instantiated with Class.forName(...).getDeclaredConstructor()
# .newInstance(). R8 sees no reference to any of them. Without this rule the release build loads keys
# and generates them fine, but the first connection dies in the key exchange with
#   TransportException: git@github.com:...: java.lang.ClassNotFoundException: com.jcraft.jsch.DHEC256
# and every row of section 5 and 6 of the spike screen fails. Keeping the no-argument constructors is
# enough: the interfaces (Cipher, HASH, Signature, KeyExchange, UserAuth, KeyPairGen*) are referenced
# from JSch's own code, so the implementations' overriding methods are kept with the classes.
-keep class com.jcraft.jsch.** { <init>(); }
