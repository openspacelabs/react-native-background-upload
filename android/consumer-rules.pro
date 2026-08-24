# These rules go to consumers through consumerProguardFiles. Thus a minified
# release build of the host app keeps these guarantees.
#
# Gson persists Upload, NotificationConfig, and EventJournal.Entry. Upload goes
# into WorkManager input data. NotificationConfig goes into SharedPreferences.
# Entry goes into the on-disk event journal. The library reads them back later,
# across app restarts AND across app updates. Gson finds fields by name through
# reflection. Gson also needs the generic Signature attribute to rebuild typed
# collections. Thus, if R8 renames a field or removes Signature, it corrupts the
# persisted state silently:
#
#   * Upload.acceptStatus is a List<Int>. Without Signature, Gson decodes it as
#     List<Double>. Then acceptStatus.contains(code) never matches, and a
#     configured accept status (for example 409) is reported as an http error,
#     not as a completed upload.
#   * A journal Entry from an older build fails to parse if field names changed.
#     The library then drops the Entry as malformed. This loses the terminal
#     outcomes that the journal exists to keep.
#
# Debug builds are not minified and round-trip correctly. Thus neither failure
# is reproducible without R8. Keep these rules.
-keepattributes Signature
-keepattributes *Annotation*

-keep class ai.openspace.backgroundupload.Upload { *; }
-keep class ai.openspace.backgroundupload.Upload$* { *; }
-keep class ai.openspace.backgroundupload.NotificationConfig { *; }
-keep class ai.openspace.backgroundupload.EventJournal$Entry { *; }
