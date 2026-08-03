# Shipped to consumers via consumerProguardFiles, so a minified release build of
# the host app keeps these guarantees.
#
# Upload and EventJournal.Entry are persisted with Gson — Upload into WorkManager
# input data, Entry into the on-disk event journal — and read back later, across
# app restarts AND across app updates. Gson resolves fields reflectively by name
# and needs the generic Signature attribute to reconstruct typed collections, so
# R8 renaming either one corrupts persisted state silently:
#
#   * Upload.acceptStatus is a List<Int>. Without Signature, Gson deserializes it
#     as List<Double>, so acceptStatus.contains(code) never matches and a
#     configured accept status (e.g. 409) is reported as an http error instead of
#     a completed upload.
#   * A journal Entry written by an older build fails to parse if field names
#     changed, and is then dropped as malformed — losing exactly the terminal
#     outcomes the journal exists to preserve.
#
# Debug builds are unminified and round-trip symmetrically, so neither failure is
# reproducible without R8; keep these rules.
-keepattributes Signature
-keepattributes *Annotation*

-keep class ai.openspace.backgroundupload.Upload { *; }
-keep class ai.openspace.backgroundupload.Upload$* { *; }
-keep class ai.openspace.backgroundupload.EventJournal$Entry { *; }
