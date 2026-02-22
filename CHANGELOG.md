## Changelog for HexDroid
# Updated 22/02/26
---
# v1.5.4
---
# App improvements
-	Fixed compilation warnings/deprecations
-	Themed adaptive icon: the app icon now includes a monochrome layer for Android 13+
-	Battery life improvements: Compose now uses collectAsStateWithLifecycle to improve battery usage in background

# UI improvements
-	Drag and drop networks to change their position
	as displayed in the Networks page/sidebar
-	Added a new "Matrix" style theme
-	MOTD on connect now resizes to fit server buffer
-	Improved messaging input box
-

# Utilities
-	Improved channel op tools
-	Added some IRCop tools (displayed in menu when umode +o)
-	Added an option to backup network/settings into a json file

# Connection
-	Fixed some SSL issues for certain MediaTek/MIUI/OneUI devices
-	Made some changes to properly support bouncers such as ZNC/Soju