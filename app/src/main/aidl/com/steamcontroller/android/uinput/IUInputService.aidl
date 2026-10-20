package com.steamcontroller.android.uinput;

import android.os.ParcelFileDescriptor;

// Runs in the Shizuku user service process (shell UID).
//
// Fronts the virtual-output backends: /dev/uinput when the shell UID is allowed to open
// it, /dev/uhid otherwise. See cpp/output_backend.h.
interface IUInputService {
    // Probe the backends and adopt the most capable usable one.
    // Returns the chosen backend id (0 = none usable, 1 = uinput, 2 = uhid).
    int selectBackend();

    // Human-readable probe result for every backend, e.g.
    // "/dev/uinput: Permission denied, /dev/uhid: ok". Shown in the UI.
    String getBackendDetail();

    // Whether games can receive rumble through the selected backend.
    boolean supportsRumble();

    // Create the virtual devices for the given profile id (see GamepadProfile.kt).
    // Returns true on success.
    boolean createGamepad(int profileId);

    void sendFrame(int buttons,
                   int leftStickX, int leftStickY,
                   int rightStickX, int rightStickY,
                   int leftTrigger, int rightTrigger,
                   int dpadX, int dpadY);

    // Desktop / mouse-mode frame. `keys` is a MouseTarget bitmask.
    void sendMouseFrame(int relX, int relY, int scrollY, int keys);

    // Returns [strongMagnitude, weakMagnitude] in 0..65535 if a game triggered rumble,
    // or null if nothing happened since the last poll.
    int[] pollForceFeedback();

    // Run a shell command as the Shizuku shell user. Returns the exit code, or -1 if the command
    // failed, timed out, or is not in ShellAllowlist - only the exact commands the app uses run.
    int runShellCommand(in String[] cmd);

    // Same, but returns captured stdout (trimmed, capped) instead of the exit code, or null on failure.
    // Used to read a Settings value before overriding it, so it can be restored later.
    String runShellCommandForOutput(in String[] cmd);

    // Self-update: runs exactly `pm install -r -S <size>` with the APK's bytes on stdin, read from
    // [apk] (a file in the app's private storage; the app verified package, signer and version
    // first). Returns pm's exit code, or -1 if refused or failed.
    int installApk(in ParcelFileDescriptor apk, long size);

    void destroy();
}
