# Native entry points and native-created exceptions are retained by :torrent's consumer rules.
# Framework-created activities/application are retained through the manifest.

# App-owned subtitle output uses this pinned Media3 integration point.
-keepclassmembers class androidx.media3.ui.SubtitleView {
    private void setView(android.view.View);
}
