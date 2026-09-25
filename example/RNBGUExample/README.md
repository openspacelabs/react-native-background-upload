# RNBGU example: the v10 device test harness

This app is a test harness for `react-native-background-upload` v10. Each step
of the device test script below can be done from its one screen. It uses the
library from `../../src` through Metro, so a change to the library shows after
a reload.

The request kinds, the listeners, and `configure()` are in
`harness/uploads.ts`. The screen is `App.tsx`.

## Run it

1. Start the test server on the Mac:

   ```sh
   cd example/server
   yarn install
   yarn start
   ```

   It listens on port 3000 and prints its LAN addresses and host name. The
   environment variables `PORT`, `AUTH_TOKEN` (default `Bearer good`),
   `SLOW_MS` (default 30000), and `SLOW_AUTH_MS` (default 20000) change it.

2. Install and start the app:

   ```sh
   cd example/RNBGUExample
   yarn install
   (cd ios && pod install)
   yarn start
   yarn android   # or: yarn ios
   ```

3. Set the "Server" field on the screen, then press "Ping server":

   | Where the app runs | Server field |
   | --- | --- |
   | Android emulator or USB device | `http://localhost:3000`, after `adb reverse tcp:3000 tcp:3000` |
   | iOS simulator | `http://localhost:3000` |
   | iOS device | `http://<mac-name>.local:3000`, or a LAN address the server printed |

   Android permits cleartext HTTP in debug builds only. iOS asks for
   local network access the first time: allow it.

Logs to watch:

- The server log: one line per request, with its size, MD5, `X-Request-Id`,
  `Authorization`, `Content-Range`, and the number of open requests.
- Metro: every line of the in-app log, with the prefix `[rnbgu]`.
- Android: `adb logcat -s RNFileUploader`. iOS: the Xcode device console
  (`[RNFileUploader]` lines report store and journal write failures).

## The server

The first path segment selects what the server does. The app puts the mode you
select into every new request path. Counters are per full path, and the app
makes a new path for each request.

| Mode | Answer |
| --- | --- |
| `ok` | 200 |
| `flaky` | 503 for the first 2 hits of the path, then 200 |
| `auth` | 401 unless `Authorization` is `AUTH_TOKEN` |
| `auth-once` | 401 for the first hit of the path, then 200 |
| `auth-slow` | waits `SLOW_AUTH_MS`, then answers like `auth` |
| `bad` | 400 |
| `gone` | 404 |
| `conflict` | 409 with `Upload already completed` in the body |
| `down` | 503, always |
| `slow` | waits `SLOW_MS`, then 200 |
| `big` | 200 with a body over 1 MB |

For a request with a `Content-Range` header (a chunked part), the server
writes the bytes at their offset into `example/server/received/`. It prints
`DUPLICATE part` when a part path is accepted a second time, and
`ASSEMBLED ... md5=...` when the file is complete. Compare that MD5 with the
`file ... md5=` line in the app log. `GET /stats` returns `open` and
`maxOpen`. The "Server stats" button reads it.

## The screen

- **Server and auth.** The server field, "Ping server" (a plain `fetch`), and
  "Server stats". The headers provider of `configure()` sends the value shown
  under "Headers provider sends". It starts as `Bearer bad` at each launch.
  Type a value in "Authorization" and press "updateHeaders + use for new
  requests": that calls `updateHeaders({ Authorization })` and makes new
  requests use the same value.
- **Options for new requests.** The server mode. "Id" sets the id of the next
  request (blank gives a UUID). "Expires in s" sets `expiresAt` to now plus
  that many seconds. "File size MiB" sets the size of the file and chunked
  uploads (chunked parts are 5 MiB). "Silent" sends
  `android: { noNotification: true }`. "Handlers throw" makes `onSuccess` and
  `onError` throw after they log, so outcomes stay unacknowledged. The app
  keeps these two switches and the server field after a relaunch.
- **Enqueue.** "JSON POST" (with a `response` parser), "Multipart photo" (a
  JSON string part and a JPEG part; the app deletes the source after
  `mutate()` resolves), "File PUT" (source deleted after `mutate()`), "Chunked
  PUT" (the log says whether the source was moved), "DELETE" (bodiless),
  "GET (no vars)" (bodiless, `mutate()` with no arguments), "3 JSON at once",
  "Cap test" (resets the server counters, then 3 chunked and 3 JSON uploads on
  `slow`), "Missing file", and "GET with a body".
- **Queue.** `pause()`, `resume()`, `cancel('nope')`, and a `setWifiOnly`
  switch. Native keeps the Wi-Fi setting and has no getter, so the switch
  shows the last value this app set. Below are the rows of `getRequests()`,
  read every second and on each `state` event. Each row has "Cancel", "Same
  id, same body" (`mutate()` again with the stored vars), and "Same id, new
  body" (the path changes to the selected mode plus `-b`, so the URL, and
  thus the body, is different). The two "Same id" buttons show only on JSON,
  DELETE, and GET rows. The file-backed rows have no source file left, so a
  second `mutate()` would reject with `E_FILE_MISSING`.
- **Journaled before this launch.** Outcomes whose native time is before this
  launch. They were journaled by an earlier process and replayed after
  `configure()`.
- **Log.** Actions and their results or rejection codes, `attempt` events,
  `state` events, and outcomes. The chips filter it. A decrease in `progress`
  for one id shows as a `warn` line.

The log shows the first 8 characters of each `X-Request-Id`. The server shows
the full value.

Kill means: swipe the app away from recents. Force stop means:
`adb shell am force-stop com.rnbguexample`. To look at the Android files:
`adb shell run-as com.rnbguexample ls -R files/rnbgupload-chunked files/rnbgupload-settled`.

## Device test script

Run it on real devices. The steps come from the Android and iOS slice notes.
Before a step, set the mode, then press the buttons it names. "Pass" is what
must be true.

If you pressed "updateHeaders" with `Bearer good` in an earlier step, set it
back to `Bearer bad` before a step that needs a 401.

### Android

1. **JSON POST.** Mode `ok`. Press "JSON POST". Pass: `state` goes `queued`,
   `running`, `completed`. One `attempt` with `http=200`. The server line has
   the same request id, `type=application/json`, and body `{"n":1,"text":"hi"}`
   (an integer, not `1.0`). The outcome shows `deliveries=1`. The row goes
   away, and the entry directory is gone from `rnbgupload-chunked`.
2. **Multipart.** Mode `ok`. Press "Multipart photo". Pass: the server shows
   a `meta` part with `type=application/json` and a `photo` part with
   `filename=photo.jpg type=image/jpeg`. The log says the source was deleted,
   and the upload still completes.
3. **Chunked.** Mode `ok`, size 30. Press "Chunked PUT 30 MiB". Pass: 6 parts.
   The server never shows `open` above 3 for these parts. The row's progress
   rises one time across the parts. The outcome says `chunked status=none`.
   The log says the source was moved. `ASSEMBLED` MD5 equals the app's file
   MD5. The entry directory is gone after the ack.
4. **Cap of 4.** Press "Cap test", wait for the parts to start, then press
   "Server stats". Pass: `maxOpen` is at most 4.
5. **Pause and resume.** Mode `ok`, size 50. Press "Chunked PUT", then
   "pause()" while it runs. Pass: every live row shows `paused` within a
   second, the server gets no new bytes, and no outcome appears. Press "JSON
   POST": its row shows `paused`. Press "resume()". Pass: rows go `queued`,
   then `running`. The server shows no `DUPLICATE part`. The JSON request runs.
6. **Cancel.** (a) Mode `ok`, size 50. Press "Chunked PUT", then "Cancel" on
   its row. Pass: `state` shows `cancelled`, no outcome runs (a cancel calls
   no handler), and the row and its bytes go away. (b) Mode `bad`. Press "JSON
   POST" and wait for the `error` row. Press "Cancel". Pass: the row goes away
   and no `state` event appears. (c) Press "cancel('nope')". Pass: it resolves.
7. **401 park and updateHeaders.** Mode `auth`. Press "3 JSON at once". Also
   press "Chunked PUT" (size 10). Pass: each row shows `awaiting-auth` after
   one `http=401` attempt, with no more attempts. Type `Bearer good` and press
   "updateHeaders". Pass: rows go `queued`, `running`, `completed`, and the
   server shows `auth=Bearer good` on each request and on every part.
   Variant: set `Bearer bad` again, mode `auth-slow`, press "JSON POST", and
   press "updateHeaders" with `Bearer good` during the 20 s wait. Pass: the
   401 is followed at once by a second attempt, and no `awaiting-auth` row
   appears.
8. **Transient backoff.** Mode `down`. Press "JSON POST". Pass: attempts at
   about 1, 2, 4, 8, and 16 s, with the row `running`. After 30 s the row
   turns `queued` with "next in Ns", and the next attempt comes near that
   time. Keep it for 10 minutes: the gaps keep growing (64 s, 128 s, and on)
   and do not drop back to 1 s. `adb shell dumpsys jobscheduler | grep -A3 '#wake'`
   shows the wake job. Cancel the row after.
9. **Expiry.** Mode `down`, "Expires in s" 20. Press "JSON POST". Pass: an
   outcome `error errorKind=expired` at about 20 s. The row stays `error`.
   "Cancel" clears it. Variant: mode `auth` with `Bearer bad`, expiry 20:
   the row parks, then settles `expired` at about 20 s. Clear "Expires in s"
   after.
10. **Kill and relaunch.** Mode `ok`, size 50. Press "Chunked PUT" and kill
    the app at about 40 %. Pass: the server keeps getting parts and prints
    `ASSEMBLED`, and a file appears in `rnbgupload-settled/`. Open the app.
    Pass: "Journaled before this launch" shows the outcome with
    `deliveries=1 JOURNALED`, one time.
    Then turn on "Handlers throw" and press "JSON POST". Kill and open the
    app two times. Pass: the outcome comes again at each launch, and
    `deliveries` grows by one each time. Turn "Handlers throw" off and open
    the app one more time to acknowledge it.
    Then mode `slow`, press "JSON POST", and force stop during the 30 s wait.
    Open the app. Pass: the row shows `queued`, not `running`, and the
    request then completes. The launch line in the log counts the rows by
    state as native reported them before `configure()`.
11. **Wi-Fi only.** On cellular, turn on "setWifiOnly". Mode `ok`. Press "JSON
    POST". Pass: the row is `running`, the notification says "Waiting for
    Wi-Fi...", and no attempt appears. Join Wi-Fi. Pass: the attempt starts.
    Kill and open the app, leave Wi-Fi, and press "JSON POST" again. Pass: it
    waits again, because native kept the setting. Turn the switch off after.
12. **v9 upgrade.** Install the v9.0.0 example. Complete one upload and kill
    the app before the JS ack. Start one chunked upload and kill it midway.
    Install this build and open it. Pass: the rows show one `legacy` row in
    state `completed`. "Cancel" clears it. No v9 WorkManager job runs, and
    `rnbgupload-events/` is empty. The same-id chunked resume needs the v9
    part URLs, so this screen cannot do it. Test that part in Diana.
13. **Same-id rules.** (a) Id `same1`, mode `slow`. Press "JSON POST". While
    it runs, press "Same id, same body" on its row. Pass: it resolves and the
    server shows one request only. Press "Same id, new body". Pass: rejected
    with `code=E_RUNNING`. (b) Id `same2`, mode `bad`. Press "JSON POST" and
    wait for `error`. Select mode `ok` and press "Same id, new body". Pass: it
    runs again and settles `ok` one time. (c) Turn on "Handlers throw". Id
    `same3`, mode `ok`. Press "JSON POST". The row stays `completed`. Press
    "Same id, same body". Pass: the outcome comes again with `deliveries` one
    higher, and the server gets no new request. Turn "Handlers throw" off,
    press "Same id, same body" again to acknowledge it, and clear "Id".
14. **Rejections.** Press "Missing file". Pass: rejected with
    `code=E_FILE_MISSING`. Press "GET with a body". Pass: rejected. The JS
    layer rejects it before native, so there is no code.

15. **Dispatch latency.** Mode `ok`, app in the foreground. Press "JSON POST"
    and note the clock on the log line the press writes. Note `at=` on the
    first attempt line for that id. The difference is the time from `mutate()`
    to the first send. Repeat five times and report the median. This number
    decides whether a follow-up adds an in-process fast path for small
    requests: a median under about 500 ms means no.

### iOS

Set Settings > Developer > Network Link Conditioner > "3G" on the phone, so an
upload is slow enough to background the app during it. Use size 50 unless a
step says otherwise.

1. **Suspended.** Mode `ok`. Press "File PUT", then press Home within 2 s.
   Wait for the server line, then return. Pass: the server shows one PUT of
   52428800 B. The log shows one `attempt ... completed`, one outcome with
   `deliveries=1`, and the row goes away.
2. **Terminated by the system.** Press "Chunked PUT" three times. Background
   the app and open heavy apps until iOS ends it (or wait). Pass: the server
   shows each part path one time, with no `DUPLICATE part`. On the next
   launch, the "Journaled" section shows an outcome for each upload with
   `deliveries` of 1 or more.
3. **Relaunch on completion.** Press "File PUT", background the app, stop it
   from Xcode (not a swipe), and wait for the server line. Pass: the server
   gets the PUT while the app is not running. On the next launch, the
   "Journaled" section shows the outcome with `deliveries=1`.
4. **Force-quit.** Press "File PUT" and swipe the app away during the upload.
   Open it. Pass: no error or cancelled outcome. The row is `queued` or
   `running` again, and the server gets a new PUT with a new request id.
5. **JS reload.** Press "File PUT", then press r in Metro during the upload.
   Pass: after the reload, one outcome with `deliveries=1`, and no second one.
6. **401 park.** Mode `auth`. Press "3 JSON at once". Pass: three rows
   `awaiting-auth`. The server shows one hit for each path, and no retries.
   **6b.** Type `Bearer good` and press "updateHeaders". Pass: three rows go
   `queued`, `running`, and three `ok` outcomes follow. The server shows
   `auth=Bearer good`.
7. **401 under an old generation.** Set `Bearer bad` again. Mode `auth-slow`.
   Press "3 JSON at once", then "updateHeaders" with `Bearer good` during the
   20 s wait. Pass: the three 401s arrive after the update, so no row shows
   `awaiting-auth`. Each request goes again at once with `auth=Bearer good`
   and gets `ok`.
8. **Pause and resume.** Press "Chunked PUT" three times. After some parts,
   press "pause()". Pass: all rows `paused`, server traffic stops, no outcome.
   Press "resume()". Pass: parts continue from the next part that was not
   accepted. The server shows no `DUPLICATE part`.
9. **Cancel live.** Press "Chunked PUT" three times. After 2 parts of one,
   press "Cancel" on its row. Pass: one `state ... cancelled` for it, no
   outcome, the row goes away, and the server shows no later lines for its
   path.
10. **Cancel settled.** Mode `bad`. Press "JSON POST" and wait for the
    `errorKind=http` outcome. Press "Cancel" on its row. Pass: the row goes
    away at once, and no event appears.
11. **Wi-Fi only.** Turn on "setWifiOnly", turn off Wi-Fi, mode `ok`, press
    "JSON POST". Pass: the row is `running`, and the server shows nothing.
    Turn on Wi-Fi. Pass: `ok`. Then with mode `flaky`, press "JSON POST" and
    turn the switch while the row waits for its retry. Pass: the retry moves
    to the other session and completes.
12. **Backoff while locked.** Mode `flaky`. Press "JSON POST" and lock the
    phone at once for 1 minute. Pass: the server shows hits 1, 2, and 3 at
    about 0 s, +1 s, and +3 s. After unlock, the outcome is `ok` with
    `attempts=3`. The row shows "next in Ns" between attempts.
13. **Expired.** Mode `down`, "Expires in s" 60. Press "JSON POST". Pass: an
    outcome `errorKind=expired` about 60 s later, and the row stays `error`.
    Press "Same id, same body" on it. Pass: the entry opens again and runs
    (a new generation). Cancel it and clear "Expires in s".
14. **v9 import.** Install the v9.0.0 build. Start one chunked upload (half
    done) and one simple upload that errors, then kill the app. Install this
    build. Pass: the rows show a `legacy` row for the errored upload, and
    the half-done chunked id is absent. The server shows no v9 traffic after
    the upgrade. The same-id chunked resume needs the v9 part URLs, so test
    it in Diana.
15. **Same-id rules.** Do Android step 13. The case "same body after cancel,
    before the ack" is not reliable from this screen, because the ack comes
    right after the cancel. The JS unit tests cover it.
16. **Before first unlock.** Turn on airplane mode. Mode `ok`, size 5. Press
    "File PUT". Restart the phone, turn off airplane mode from Control Center
    without an unlock, and wait 2 minutes. Pass: the Xcode device console
    shows the attempt fail with a `file` error and no terminal outcome. After
    unlock, open the app: the outcome is `ok`.
17. **Bodiless GET and DELETE.** Mode `ok`. Press "GET (no vars)" and
    "DELETE". Pass: the server shows a GET and a DELETE with 0 B, and both
    outcomes are `ok`. If one retries without end with a `network` error,
    write down the error message: a bodiless GET needs a download task.

18. **Dispatch latency.** Same as Android step 15. iOS creates a background
    session task per attempt, so this is the number to watch.

## Tests

`yarn test` in this folder runs a Jest smoke test of the screen against a stub
of the native module. It checks that `configure()` runs at boot and that the
buttons reach native with the right descriptors.
