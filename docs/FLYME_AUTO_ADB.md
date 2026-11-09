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
- The public [Flyme Auto/Geely community notes](https://lynkco.hyphp.cn/t/1982.html)
  list Xingyuan among E02 head units and report that some E02 firmware exposes
  wireless ADB in engineering mode. That is a manual vehicle-side switch, not a
  supported app API. The reported GeelyCloud E22H / Kona / Qualcomm identifiers
  do not establish that the unit uses the same firmware path, so the engineering
  menu procedure must not be automated from this evidence.

## Latest uploaded vehicle log

The 2026-11-09 report (`v0.2.16-b261109-002`) identifies Android 11/API 30,
GeelyCloud E22H, Kona/Qualcomm. CarPlay setup reports `ready`, but the session is
`stopped`, no display negotiation exists, and `selectedPhoneConfigured=false`.
The owner confirmed the iPhone is paired over Bluetooth and the car hotspot is
connected. The ADB attempt recorded `WRITE_SECURE_SETTINGS=false`,
`ADB_ENABLED=0`, and localhost port 5555 open; Flyme Auto rejected the protected
settings write with `SecurityException`. That permission is not required for
normal CarPlay networking. The Android 11 gap was that the existing-Wi-Fi
transport requested no runtime Location permission, even though Android 10+
Wi-Fi APIs require it. DiPlay now requests Location for Wi-Fi on Android 12 and
older, Nearby Wi-Fi on Android 13+, and Bluetooth Connect on Android 12+. A
denied request opens DiPlay app settings and logs each grant result. If exactly
one Bluetooth device is paired, DiPlay selects it automatically; with multiple
devices it still asks the user. These changes use the existing Bluetooth pairing
and car hotspot without changing Flyme Auto's protected ADB setting.

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
