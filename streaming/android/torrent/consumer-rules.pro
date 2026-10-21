# Called from native code by name: shrinking must keep the native methods and the exception's constructor.
-keepclasseswithmembernames,includedescriptorclasses class tv.daniil.torrent.TorrentEngine {
    native <methods>;
}
-keep class tv.daniil.torrent.TorrentException {
    <init>(int, java.lang.String);
}
