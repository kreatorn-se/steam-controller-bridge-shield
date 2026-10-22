// The radial-menu keyboard: one standard boot keyboard on /dev/uhid, separate from the
// backends' sidecar keyboard (whose gamepad-mode descriptor deliberately omits the alphabet).
// It only exists while the active profile has key actions, because a device that declares
// A-Z makes Android believe a hardware keyboard is attached.
//
// Not thread-safe on its own: every call happens under uinput_jni.cpp's g_lock.
#pragma once

#include <stdint.h>

namespace radial_kbd {

// Creates the device if it does not exist. True when it exists afterwards.
bool create();

// One 8-byte boot report: [modifiers, 0, k1..k6]. `count` keys (0..6) from `keys`.
bool send(uint8_t modifiers, const uint8_t* keys, int count);

// Answers anything the kernel queued (SET_REPORT for the LEDs, GET_REPORT). Called at 50 Hz.
void drain();

// Releases every key, destroys the device and closes the node. Safe when nothing exists.
void destroy();

bool exists();

}  // namespace radial_kbd
