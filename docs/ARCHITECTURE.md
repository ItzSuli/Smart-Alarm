# Architecture

## Modules

```
core/     pure Kotlin, no Android. Models, the sleep engine, the wire protocol.
wear/     Wear OS app. Owns the sensors and the wake decision.
mobile/   Phone app. Planning, live view, history, and the backstop alarm.
```

`core` is a plain JVM library on purpose: the sleep engine is the part worth testing, and
keeping it free of Android means its tests are ordinary JUnit that run in seconds.

Both apps build to application id **`com.smartalarm`** and are signed with the same key.
That is not tidiness — the Wearable Data Layer refuses to connect a phone app and a watch app
that differ in either.

## Who decides what

The watch is authoritative for the alarm. It has the sensors, it runs the engine, it decides
when to fire. The phone is told what happened but is never asked what to do, so a Bluetooth drop
at 2 a.m. cannot stop the alarm at 6.

The phone replays the same epoch stream through the same `SleepSessionEngine`. That gives it a
hypnogram and history that cannot disagree with the watch's decision, and means a night still
renders correctly if the watch's end-of-night summary never arrives.

The phone has exactly one piece of independent authority: an `AlarmManager.setAlarmClock` parked
at the hard deadline. It is re-armed whenever the projection moves and after a reboot, and
cancelled the moment the watch fires. If the watch has been silent for ten minutes when it goes
off, the phone assumes the watch is dead and rings regardless of the chosen wake mode — a flat
watch battery should not mean sleeping through the morning.

## The wire

Two transports, chosen for different failure modes.

**`DataClient`** carries state: the session request, the live status, the epoch stream, the
finished summary. Data items are replicated and persistent, so a phone left downstairs receives
everything it missed the moment it comes back into range. Epoch batches each get their own path
(`/smartalarm/epochs/<sequence>`) so an uncollected batch is never overwritten by the next one.

**`MessageClient`** carries events: start, stop, wake now, dismiss, snooze. Low latency, and a
message that cannot be delivered is no loss — the watch has already buzzed, and the phone has
its own backstop scheduled.

Everything is UTF-8 JSON via `kotlinx.serialization`, decoded leniently with
`ignoreUnknownKeys` so a newer peer never breaks an older one. `ProtocolTest` round-trips every
message type, because the two APKs only ever meet over the wire and a field that fails to
round-trip is a bug neither app's own tests would catch.

| path | direction | transport | payload |
|---|---|---|---|
| `/smartalarm/session` | phone → watch | Data | `SessionRequest` |
| `/smartalarm/status` | watch → phone | Data | `LiveStatus` |
| `/smartalarm/epochs/<n>` | watch → phone | Data | `EpochBatch` |
| `/smartalarm/summary` | watch → phone | Data | `SessionSummary` |
| `/smartalarm/event/start` | phone → watch | Message | `SessionRequest` |
| `/smartalarm/event/stop` | phone → watch | Message | `AlarmEvent` |
| `/smartalarm/event/wake` | watch → phone | Message | `AlarmEvent` |
| `/smartalarm/event/dismiss` | either | Message | `AlarmEvent` |
| `/smartalarm/event/snooze` | either | Message | `AlarmEvent` |
| `/smartalarm/event/state` | watch → phone | Message | `StateChanged` |

## Running for ten hours on a watch

**Sensor batching.** The accelerometer runs at 20 Hz and the heart-rate sensor every ten
seconds, both registered with a thirty-second maximum report latency. The sensor hub buffers in
hardware and hands over one burst instead of interrupting the main processor twenty times a
second. Just before the wake window opens the buffer is flushed explicitly, so the decision is
made on fresh data rather than on a batch up to thirty seconds old.

**Timestamps.** Batched events arrive long after their samples were taken and are stamped
against elapsed-realtime. Each sample is converted back to the wall-clock instant it was
actually recorded; using the delivery time would collapse a whole batch into one epoch.

**Wake lock.** A partial wake lock is held for the session. Sleep tracking that lets the
processor sleep loses data, and a night that silently records nothing is worse than a night that
costs a quarter of the battery.

**The loop.** A five-second timer closes finished epochs, re-evaluates the wake decision and
updates the notification; the phone is pushed to once a minute. The wake decision is re-evaluated
on the timer rather than only when an epoch arrives, because the hard deadline has to fire even
if the sensors have gone completely quiet.

## Storage

Finished nights are one JSON document each under `files/nights/`; the night in progress is a
JSON-lines file under `files/active/` that epochs are appended to as they arrive.

Append-only per-night documents fit the data better than a relational schema: a night is read
whole or not at all, never joined or queried across. Epochs are de-duplicated by index on read,
because a watch reconnecting after a gap may republish a batch the phone already has. The format
is also readable with `cat`, which matters when something needs explaining at six in the
morning.

The plan and the learned profile live in SharedPreferences — read synchronously from broadcast
receivers and short-lived services where a suspending read is the wrong shape.

## Process death

The phone's process will be killed during the night. On restart, `SessionManager.restore()`
reads the active session id, replays its epochs through a fresh engine, and re-arms the backstop
alarm. `BootReceiver` does the same after a reboot, since exact alarms do not survive one.

If the watch starts a night the phone does not know about — you pressed start on the watch — the
phone adopts the session when the first epoch batch arrives.

## Testing

`core` has the test suite, because `core` is where the thinking is. `SyntheticNight` builds a
night from a known hypnogram and drives it through the real signal chain from raw 20 Hz samples;
see [ALGORITHM.md](ALGORITHM.md#validation).

The Android layers are deliberately thin — services, a bridge, and Compose — and are covered by
the build rather than by instrumentation tests, which cannot run without the physical watch this
app is written for.
