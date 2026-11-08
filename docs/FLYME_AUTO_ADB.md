# Flyme Auto ADB attempt

The button labelled `允许ABD` is an experimental, user-triggered attempt to make
DiPlay's existing local ADB client available on a Flyme Auto head unit. Use it
only while the vehicle is parked.

## Evidence and limits

- The official [Flyme Auto open platform](https://open.flymeauto.com/) lists
  Geely Xingyuan as a Flyme Auto customer case, but publishes no application API
  for enabling ADB.
- Community Xingyuan/EX2 inspection identifies an Android-based ECARX head unit,
  but this is not manufacturer documentation and firmware variants may differ.
- Some Flyme Auto versions expose an ADB switch in an engineering or developer
  screen; reports also show later firmware hiding that switch.
- A community unlocker redirects a vehicle MQTT connection and sends
  `ADBSwitch=ON`. DiPlay deliberately does not embed DNS redirection, MQTT
  impersonation, engineering passwords, patched system files or remote services.

An ordinary Android app cannot grant itself `WRITE_SECURE_SETTINGS`. Declaring
the permission makes an already privileged or explicitly provisioned install
usable, but does not bypass the platform signature check.

## Attempt sequence

1. Record manufacturer, model, product, Android API and app UID.
2. Check whether `WRITE_SECURE_SETTINGS` is already granted.
3. Read `Settings.Global.ADB_ENABLED`.
4. Attempt to write the standard Android ADB switch and record the result.
5. Read the switch again.
6. Probe only `127.0.0.1:5555` with a 600 ms timeout.
7. If it is open, offer DiPlay's existing ADB key for the normal on-screen
   authorization prompt.
8. If direct access is unavailable, open Android developer settings, falling
   back to the general system settings page.

No arbitrary shell text, vehicle-control command, external server or credential
is used by this sequence.

## Diagnostics

Every stage records start, completion/failure, exception class and elapsed time
in the private bounded files `logs/adb-enable.log` and
`logs/adb-enable-previous.log`. These files are included in saved and uploaded
diagnostic reports under `ADB allow attempts`. Upload a report after reproducing
the problem so firmware-specific behavior can be added without guessing.
