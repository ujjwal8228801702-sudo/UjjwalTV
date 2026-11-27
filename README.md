# HD-TV-App

Android TV live playlist player.

## Current features
- M3U playlist loading (default India playlist URL)
- Channel categories and channel logos when supplied by the playlist
- Favourite channels
- Remote-control keyboard navigation and numeric channel selection
- Full-screen video playback
- Home, Live TV and Guide navigation

## Notes
- Live channel availability depends on the playlist and the rights/availability of each stream.
- The Guide lists channels. Actual programme schedules require EPG data in the playlist; timings are not fabricated.
- Phone remote over the same Wi-Fi: open Home → Phone Remote on TV, visit the shown `http://TV-IP:8765/remote` address on your phone, and enter the 4-digit TV PIN. Supports D-pad, OK, Back, Play/Pause and volume keys. Keep the TV app open while using the remote.
- Samsung Tizen requires a separate Tizen package; this repository builds an Android APK.
