# These rules go to consumers through consumerProguardFiles. Thus a minified
# release build of the host app keeps these guarantees.
#
# Gson persists the queue entry (entry.json), the queue settings
# (settings.json), the settled-outcome journal, NotificationConfig
# (SharedPreferences), and reads the v9 journal and v9 chunked manifests at
# the first v10 launch. The library reads them back later, across app
# restarts AND across app updates. Gson finds fields by name through
# reflection, and it needs the generic Signature attribute to rebuild typed
# collections. Thus, if R8 renames a field or removes Signature, it corrupts
# the persisted state silently:
#
#   * Descriptor.accept is a List<AcceptRule> and Descriptor.parts a
#     List<Part>. Without Signature, Gson decodes the elements as bare maps.
#     Then no accept rule matches, and every chunked part reads as unsent.
#   * A record from an older build fails to parse if field names changed. The
#     library drops it as malformed. That loses the outcomes the journal
#     exists to keep, and the entries the queue exists to run.
#   * EntryState is an enum persisted by its @SerializedName wire string.
#
# Debug builds are not minified and round-trip correctly, so neither failure
# is reproducible without R8. Keep these rules.
-keepattributes Signature
-keepattributes *Annotation*

# v10 queue
-keep class ai.openspace.backgroundupload.QueueEntry { *; }
-keep class ai.openspace.backgroundupload.EntryState { *; }
-keep class ai.openspace.backgroundupload.Descriptor { *; }
-keep class ai.openspace.backgroundupload.FormPart { *; }
-keep class ai.openspace.backgroundupload.RetryOverride { *; }
-keep class ai.openspace.backgroundupload.StagedBody { *; }
-keep class ai.openspace.backgroundupload.Part { *; }
-keep class ai.openspace.backgroundupload.QueueSettings { *; }
-keep class ai.openspace.backgroundupload.RetryDefaults { *; }
-keep class ai.openspace.backgroundupload.EventJournal$SettledRecord { *; }
-keep class ai.openspace.backgroundupload.EventJournal$Response { *; }
-keep class ai.openspace.backgroundupload.UploadOutcome$AcceptRule { *; }
-keep class ai.openspace.backgroundupload.NotificationConfig { *; }

# v9 files read once at the first v10 launch
-keep class ai.openspace.backgroundupload.LegacyImport$V9Entry { *; }
-keep class ai.openspace.backgroundupload.LegacyManifest { *; }
