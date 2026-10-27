# Called from native code by name: shrinking must keep the native methods and the exception's constructor.
-keepclasseswithmembernames,includedescriptorclasses class tv.stream.torrent.TorrentEngine {
    native <methods>;
}
-keep class tv.stream.torrent.TorrentException {
    <init>(int, java.lang.String);
}
