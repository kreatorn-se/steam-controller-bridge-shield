// See radial_keyboard.h.
#define LOG_TAG "radial_kbd"
#include "radial_keyboard.h"
#include "hid_common.h"

#include <errno.h>
#include <fcntl.h>
#include <linux/uhid.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

namespace radial_kbd {
namespace {

constexpr const char* UHID_PATH = "/dev/uhid";
constexpr const char* NAME = "Steam Controller Radial Keyboard";
// pid.codes test VID/PID: deliberately not Valve's (0x28DE would invite hid-steam) and not the
// gamepad's identity, so no keylayout or driver special-cases it.
constexpr uint16_t VID = 0x1209;
constexpr uint16_t PID = 0x0001;

// HID 1.11 Appendix B.1: the boot-protocol keyboard. 8-byte input report (modifiers, reserved,
// six key usages 0x00-0x65) and the 5-bit LED output report.
constexpr uint8_t BOOT_KEYBOARD_RD[] = {
    0x05, 0x01,  // Usage Page (Generic Desktop)
    0x09, 0x06,  // Usage (Keyboard)
    0xA1, 0x01,  // Collection (Application)
    0x05, 0x07,  //   Usage Page (Keyboard)
    0x19, 0xE0,  //   Usage Minimum (Left Control)
    0x29, 0xE7,  //   Usage Maximum (Right GUI)
    0x15, 0x00,  //   Logical Minimum (0)
    0x25, 0x01,  //   Logical Maximum (1)
    0x75, 0x01,  //   Report Size (1)
    0x95, 0x08,  //   Report Count (8)
    0x81, 0x02,  //   Input (Data,Var,Abs)   modifiers
    0x95, 0x01,  //   Report Count (1)
    0x75, 0x08,  //   Report Size (8)
    0x81, 0x01,  //   Input (Const)          reserved
    0x95, 0x05,  //   Report Count (5)
    0x75, 0x01,  //   Report Size (1)
    0x05, 0x08,  //   Usage Page (LEDs)
    0x19, 0x01,  //   Usage Minimum (Num Lock)
    0x29, 0x05,  //   Usage Maximum (Kana)
    0x91, 0x02,  //   Output (Data,Var,Abs)  LEDs
    0x95, 0x01,  //   Report Count (1)
    0x75, 0x03,  //   Report Size (3)
    0x91, 0x01,  //   Output (Const)         LED padding
    0x95, 0x06,  //   Report Count (6)
    0x75, 0x08,  //   Report Size (8)
    0x15, 0x00,  //   Logical Minimum (0)
    0x25, 0x65,  //   Logical Maximum (101)
    0x05, 0x07,  //   Usage Page (Keyboard)
    0x19, 0x00,  //   Usage Minimum (0)
    0x29, 0x65,  //   Usage Maximum (101)
    0x81, 0x00,  //   Input (Data,Array)     keys
    0xC0,        // End Collection
};

int g_fd = -1;

bool write_event(const uhid_event& ev, const char* what) {
    if (::write(g_fd, &ev, sizeof(ev)) == (ssize_t)sizeof(ev)) return true;
    LOGE("%s failed: %s", what, strerror(errno));
    return false;
}

void handle(const uhid_event& ev) {
    switch (ev.type) {
        case UHID_GET_REPORT: {
            uhid_event reply;
            memset(&reply, 0, sizeof(reply));
            reply.type = UHID_GET_REPORT_REPLY;
            reply.u.get_report_reply.id = ev.u.get_report.id;
            reply.u.get_report_reply.err = (uint16_t)(-EIO);
            write_event(reply, "GET_REPORT reply");
            break;
        }
        case UHID_SET_REPORT: {
            uhid_event reply;
            memset(&reply, 0, sizeof(reply));
            reply.type = UHID_SET_REPORT_REPLY;
            reply.u.set_report_reply.id = ev.u.set_report.id;
            reply.u.set_report_reply.err = 0;
            write_event(reply, "SET_REPORT reply");
            break;
        }
        default:
            break;  // START, OPEN, CLOSE, OUTPUT (LEDs): nothing to do
    }
}

}  // namespace

bool exists() { return g_fd >= 0; }

bool create() {
    if (g_fd >= 0) return true;
    const int fd = ::open(UHID_PATH, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        LOGE("open %s failed: %s", UHID_PATH, strerror(errno));
        return false;
    }
    g_fd = fd;
    uhid_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_CREATE2;
    snprintf(reinterpret_cast<char*>(ev.u.create2.name), sizeof(ev.u.create2.name), "%s", NAME);
    snprintf(reinterpret_cast<char*>(ev.u.create2.phys), sizeof(ev.u.create2.phys), "android/uhid");
    snprintf(reinterpret_cast<char*>(ev.u.create2.uniq), sizeof(ev.u.create2.uniq), "radial/keyboard");
    ev.u.create2.rd_size = (uint16_t)sizeof(BOOT_KEYBOARD_RD);
    ev.u.create2.bus = BUS_USB;
    ev.u.create2.vendor = VID;
    ev.u.create2.product = PID;
    ev.u.create2.version = 0x0150;
    ev.u.create2.country = 0;
    memcpy(ev.u.create2.rd_data, BOOT_KEYBOARD_RD, sizeof(BOOT_KEYBOARD_RD));
    if (!write_event(ev, "UHID_CREATE2")) {
        ::close(g_fd);
        g_fd = -1;
        return false;
    }
    // UHID_START arrives once the device exists; wait for it briefly so the first report
    // cannot race the creation. Not fatal when it does not come.
    struct pollfd pfd = { g_fd, POLLIN, 0 };
    if (::poll(&pfd, 1, 100) > 0) drain();
    LOGI("created (%zu-byte boot descriptor)", sizeof(BOOT_KEYBOARD_RD));
    return true;
}

bool send(uint8_t modifiers, const uint8_t* keys, int count) {
    if (g_fd < 0) return false;
    drain();
    uhid_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_INPUT2;
    ev.u.input2.size = 8;
    ev.u.input2.data[0] = modifiers;
    for (int i = 0; i < count && i < 6; i++) ev.u.input2.data[2 + i] = keys[i];
    return write_event(ev, "UHID_INPUT2");
}

void drain() {
    if (g_fd < 0) return;
    for (;;) {
        struct pollfd pfd = { g_fd, POLLIN, 0 };
        if (::poll(&pfd, 1, 0) <= 0) return;
        uhid_event ev;
        memset(&ev, 0, sizeof(ev));
        if (::read(g_fd, &ev, sizeof(ev)) < (ssize_t)sizeof(uint32_t)) return;
        handle(ev);
    }
}

void destroy() {
    if (g_fd < 0) return;
    send(0, nullptr, 0);  // nothing stays held in the game
    uhid_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_DESTROY;
    write_event(ev, "UHID_DESTROY");
    ::close(g_fd);
    g_fd = -1;
    LOGI("destroyed");
}

}  // namespace radial_kbd
