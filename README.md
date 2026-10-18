# Pebbl Link

An open-source Android app that talks to cheap Bluetooth LE smartwatches directly, without the vendor's app. The first target is the Pebble Active+ (the Indian Pebble brand, normally paired with HryFine).

This is not an app that runs on the watch. These watches have closed firmware. Pebbl Link is a phone-side controller that sends commands to the watch over BLE and reads data back.

## Status

Early. The app is a BLE explorer: scan, connect, list GATT services and characteristics, subscribe to notifications, and send raw hex. It is the tool for mapping the watch's protocol.

## Mapping the protocol

1. Turn off HryFine on the phone (unpair it or force-stop it). The watch accepts one connection at a time.
2. In Pebbl Link, scan, connect to the watch and find the vendor service (usually a custom 128-bit UUID with one write and one notify characteristic).
3. Subscribe to the notify characteristic.
4. To learn the commands, enable Android's Bluetooth HCI snoop log, use HryFine for one action at a time, then open the log in Wireshark and note the bytes written.
5. Replay those bytes from Pebbl Link and watch the replies in the log.

## Build

Needs JDK 17+ and the Android SDK (Android Studio's bundled JDK works).

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
```

## Legal

Not affiliated with Pebble or HryFine. Names are used only to say which hardware is supported. Protocol notes come from observing the author's own watch.
