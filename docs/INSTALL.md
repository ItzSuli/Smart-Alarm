# Installing Smart Alarm

You need two APKs from the [latest release](../../releases/latest):

| file | goes on |
|---|---|
| `smartalarm-phone.apk` | your Android phone |
| `smartalarm-watch.apk` | your Galaxy Watch 4 |

> **Install both from the same release.** Wear OS only lets a phone app and a watch app talk to
> each other if they share an application id *and* a signing certificate. Mixing releases, or
> mixing a release APK with one you built yourself under a different key, means the two will
> install fine and then never see each other.

---

## 1. The phone

1. Download `smartalarm-phone.apk` on the phone.
2. Open it. Android will ask whether to allow installs from this source — allow it, then
   install.
3. Open Smart Alarm and grant the notification permission when asked. Without it the alarm
   cannot show its full-screen screen over the lock screen.

**Worth doing:** Settings → Apps → Smart Alarm → Battery → **Unrestricted**. The phone's
backstop alarm survives Doze either way, but on Samsung's aggressive battery management an app
that has been "put to sleep" may not receive data from the watch during the night.

## 2. The watch

The Galaxy Watch 4 has no store listing for this app, so it is sideloaded over ADB.

### Enable developer mode on the watch

1. **Settings → About watch → Software**
2. Tap **Software version** five times. "Developer mode has been turned on" appears.
3. Go back to **Settings → Developer options**.
4. Turn on **ADB debugging**.
5. Turn on **Wireless debugging** (sometimes "Debug over Wi-Fi").

Make sure the watch and your computer are on the same Wi-Fi network.

### Pair and connect

Wear OS 3 requires a pairing step the first time.

1. On the watch: **Developer options → Wireless debugging → Pair new device**. It shows a
   six-digit code and an IP address with a port, for example `192.168.1.42:37519`.
2. On your computer:

   ```bash
   adb pair 192.168.1.42:37519
   # paste the six-digit code when prompted
   ```

3. Then connect. The connection port is the one shown on the **Wireless debugging** screen
   itself, usually `5555`:

   ```bash
   adb connect 192.168.1.42:5555
   adb devices          # the watch should be listed as "device"
   ```

   If the watch shows `unauthorized`, look at its screen and accept the prompt.

### Install

```bash
adb -s 192.168.1.42:5555 install smartalarm-watch.apk
```

You should see `Success`.

### Grant the heart-rate permission

The app asks for this on first launch, but Wear OS permission dialogs are fiddly on a small
screen and granting it over ADB is easier:

```bash
adb -s 192.168.1.42:5555 shell pm grant com.smartalarm android.permission.BODY_SENSORS
adb -s 192.168.1.42:5555 shell pm grant com.smartalarm android.permission.ACTIVITY_RECOGNITION
adb -s 192.168.1.42:5555 shell pm grant com.smartalarm android.permission.POST_NOTIFICATIONS
```

On Wear OS 4 and later also grant background body sensors, so heart rate keeps arriving with the
screen off:

```bash
adb -s 192.168.1.42:5555 shell pm grant com.smartalarm android.permission.BODY_SENSORS_BACKGROUND
```

Without `BODY_SENSORS` the app still tracks sleep and wake, but it cannot tell deep sleep from
REM and says so on screen.

### Disconnect

```bash
adb disconnect 192.168.1.42:5555
```

Turning wireless debugging back off on the watch afterwards is a good idea — it costs battery.

---

## 3. Check they can see each other

Open the app on the phone. The pill at the top right should read **your watch's name**. If it
says "Watch not in range":

- Is the Galaxy Wearable app installed and the watch paired to this phone in the normal way?
- Are both APKs from the same release?
- Open the Smart Alarm app on the watch once. Wear OS sometimes will not route data to an app
  that has never been launched.
- Reboot the watch. Wear OS's data layer occasionally needs it.

## Updating

Install the new APK over the old one on both devices. The signing key is unchanged between
releases, so your history and learned profile survive. Update both — a phone and watch on
different versions may disagree about the wire format.

---

## Using your own signing key

The repo ships a signing key so the two APKs pair out of the box. It is a convenience, not a
secret: anyone can build an APK with the same signature. For a sideloaded personal app that is a
fair trade, but if you would rather not:

```bash
keytool -genkeypair -v -keystore my-release.jks -alias smartalarm \
  -keyalg RSA -keysize 4096 -validity 10950
```

Then build with your own credentials:

```bash
./gradlew :mobile:assembleRelease :wear:assembleRelease \
  -Psmartalarm.storeFile=/absolute/path/my-release.jks \
  -Psmartalarm.storePassword=... \
  -Psmartalarm.keyAlias=smartalarm \
  -Psmartalarm.keyPassword=...
```

Both APKs must be signed with the same key. You will need to uninstall the release-signed
version from both devices first, which loses your history.

## Troubleshooting

**The alarm did not go off.** Check the phone's battery settings (above), and check the watch
app was actually tracking — it shows an ongoing notification all night. If the watch died, the
phone's backstop alarm should have fired; it is switched on by default under Settings → Alarm.

**The watch battery drained.** A tracked night typically costs 20–30% on a Galaxy Watch 4. If it
is much worse, something else is also holding the processor awake — wireless debugging left on
is the usual culprit.

**Stages look wrong.** Wear the watch snugly, a finger's width above the wrist bone. A loose
watch gives intermittent heart-rate readings, and heart rate is what separates deep sleep from
REM.

**It woke me too early.** Reduce the smart wake window on the home screen, or set it to zero so
the alarm only ever fires at or after the target.
