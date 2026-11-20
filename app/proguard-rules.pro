-keepattributes *Annotation*

# SMBJ selects NTLM on Android and does not load the desktop Kerberos provider.
-dontwarn org.ietf.jgss.**

# SMBJ's event bus uses plain handlers. MBassador's optional EL filters stay unused.
-dontwarn javax.el.**
