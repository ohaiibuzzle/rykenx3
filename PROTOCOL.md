# RYKEN RK-X3 USB Power Meter HID Protocol

Reverse-engineered from the vendor app (`app.exe`, RYKEN 0.5.3, Tauri 2 / Rust + JS
frontend) and confirmed against a real RK-X3 (HW V1.3, FW V1.22).

Legend: **[HW]** = confirmed on hardware, **[RE]** = from the binary only (not yet
observed live).

---

## 1. Transport

| Item | Value |
|---|---|
| Interface | USB HID, usage page `0x00FF` (vendor-defined), usage `0x01` **[HW]** |
| VID / PID | `0x2E3C` / `0xAF03` (product string `RK-X3`, vendor `RYKEN`) **[HW]** |
| Report size | 64 bytes in / 64 bytes out, report ID `0x00` **[HW]** |
| Serial number | USB serial string (e.g. `27F48FEE7480`); the app opens the device by it |

On write, prefix the frame with report ID `0x00` and send it as one output report.
On read, hidapi strips the report ID, so byte 0 of a report is the frame header. Each
report carries one frame. The rest of the report is padding and should be ignored.

The vendor app uses a lock file (`ryken_device_lock_<sn>.lock`) so only one app
instance can use a device. The device has its own "claimed" state as well (see §4).

---

## 2. Frame format

```
+------+-----+-----+-------------+--------+--------+
| HEAD | LEN | CMD | payload ... | CRC_hi | CRC_lo |
+------+-----+-----+-------------+--------+--------+
  1 B    1 B   1 B    LEN-5 B       2 B (big-endian)
```

* `HEAD` = `0xAA` host→device, **`0x55` device→host** **[HW]** (the vendor app never checks it)
* `LEN`  = total frame length including HEAD and CRC (= payload length + 5)
* `CRC`  = CRC-16 over `HEAD .. last payload byte`, sent big-endian **[HW]**

### CRC-16

Non-reflected, MSB-first, poly `0x2507`, init `0x0011`, and **+1** added to the final
value (mod 2^16).

```python
def crc16(data: bytes) -> int:
    r = 0x0011
    for b in data:
        r ^= b << 8
        for _ in range(8):
            r = ((r << 1) ^ 0x2507) if r & 0x8000 else (r << 1)
            r &= 0xFFFF
    return (r + 1) & 0xFFFF
```

Test vectors: heartbeat `AA 06 06 01 A6 97`; disconnect `AA 06 05 01 04 B9`.

---

## 3. Commands (host → device)

| CMD | Payload | Purpose | Status |
|---|---|---|---|
| `0x00` | `YY MM DD hh mm` (local time, `YY` = year % 100) | Handshake / query. The timestamp doubles as the challenge. | [HW] |
| `0xFF` | ASCII `"ready"` + `SIG_hi SIG_lo` | Claim the device (completes the handshake) | [HW] |
| `0x02` | `enable, interval_hi, interval_lo` (ms; app allows 10/20/100/200/1000) | "Data sampling" setting. **Does not change the stream rate** (see §5) | [HW] accepted |
| `0x03` | `enable, type` (0 = PD, 1 = UFCS) | Protocol sniffing on/off → `0x30` frames | [RE] |
| `0x04` | `01` | Enter firmware-update mode | [RE] |
| `0x05` | `01` | Host disconnect notice. **Stops the stream** | [HW] |
| `0x06` | `01` | Heartbeat (the app sends it periodically; the interval is configurable) | [HW] accepted |

The device sends no reply to `0xFF`, `0x02`, `0x05` or `0x06`.

**Heartbeat timeout [HW]:** if a claimed session stops sending heartbeats (for example
the host crashed or closed the port without `0x05`), the meter keeps streaming and stays
claimed for about **5.4 s**, then releases itself. While it is in that stale state, a
handshake is answered with status `1` (busy) or not answered at all. Sending `0x05`
first releases the stale claim immediately; the next handshake and claim then succeed
(3 out of 3 trials). A 1 s heartbeat is well inside the timeout.

---

## 4. Handshake / claim

1. Host → `AA 0A 00 YY MM DD hh mm CRC`
2. Device → reply with `CMD = 0x00`, 26 bytes **[HW]**:

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `0x55` |
| 1 | 1 | `LEN` = `0x1A` |
| 2 | 1 | `0x00` |
| 3 | 1 | status: `0` = free, `1` = already claimed by another host |
| 4 | 12 | MCU unique ID |
| 16 | 4 | Device serial (the app shows it as hex, e.g. `250603d6`) |
| 20 | 1 | Hardware version: `V{b/10}.{b%10}` (13 → V1.3) |
| 21 | 1 | Firmware version: `V{b/100}.{b%100:02}` (122 → V1.22) |
| 22 | 2 | Signature `SIG`, big-endian |
| 24 | 2 | Frame CRC |

3. Verify `SIG == crc16(status ‖ mcu_id[12] ‖ serial[4] ‖ the 5 timestamp bytes sent)`.
   The vendor app rejects the device if this check fails.
4. Host → `AA 0C FF 72 65 61 64 79 SIG_hi SIG_lo CRC` (`"ready"` + SIG).

If you send only step 1 (no claim), you get the device info and status without
starting the stream. The vendor app does this to list devices.

Example captured exchange:
```
TX aa 0a 00 1a 09 1b 02 00 fa 5e
RX 55 1a 00 00 20 98 7b d8 74 80 40 00 07 5c 14 16 25 06 03 d6 0d 7a 0f 8a 24 d1
TX aa 0c ff 72 65 61 64 79 0f 8a f6 36
```

---

## 5. Data frames (device → host)

Streaming **starts as soon as the device is claimed** and runs at about **167 frames/s**
(~6 ms), whatever `0x02` interval is set. It **stops after `0x05`**. Nothing is sent
before the claim. **[HW]**

The vendor app samples this stream on the host at its chosen poll interval.

### CMD `0x20`: measurement (27 bytes) [HW]

All multi-byte fields are big-endian.

| Offset | Type | Field | Unit |
|---|---|---|---|
| 3 | float32 | VBUS voltage | V |
| 7 | float32 | VBUS current | A |
| 11 | uint16 | D+ | mV |
| 13 | uint16 | D− | mV |
| 15 | uint16 | CC1 | mV |
| 17 | uint16 | CC2 | mV |
| 19 | uint16 | Temperature, raw. `10000` = not present | raw (the app shows it unscaled) |
| 21 | uint16 | Phone power | ÷10 |
| 23 | 2 B | unknown (seen as `00 00`; the vendor app ignores it) | - |
| 25 | uint16 | Frame CRC | |

The host calculates power as `V × I`. Charge (Ah) and energy (Wh) are **not sent** by
the device, so the host must integrate them.

Idle capture (no load; the meter is powered from the PC):
```
55 1b 20 00000000 00000000 0c86 0c19 0001 0000 2710 0000 0000 6286
      V=0.0    I=0.0    D+=3.206 D-=3.097 CC1=0.001 CC2=0 temp=n/a phone=0
```
With nothing plugged in, D+/D− idle at about 3.1–3.2 V. They are not zero.

### CMD `0x30`: sniffed PD / UFCS message [RE]

Enabled with `0x03`. The payload (`bytes[3 .. LEN-2]`) is the raw captured
protocol message. The host adds its own timestamp. For UFCS, the vendor
frontend decodes the payload as follows:

* Header (2 B, big-endian): `addr = h>>13 & 7` (1 = source, 2 = sink, 3 = cable),
  `msg_no = h>>9 & 15`, `proto_ver = h>>3 & 63`, `type = h & 7`
  (0 = control, 1 = data, 2 = vendor)
* Control: 1 command byte (Ping, ACK, NCK, Accept, Soft_Reset, Power_Ready,
  Get_Output_Capabilities, …, Exit_UFCS_Mode = 0x0F)
* Data: `cmd, len, data…`. Commands: 1 Output_Capabilities (8 B per mode), 2 Request,
  3 Source_Info, 4 Sink_Info, 5 Cable_Info, 6 Device_Info, 7 Error_Info,
  8 Config_Watchdog, 9 Refuse, 10/11 Verify, 0xFF Test_Request
* Vendor: `vid(2), len, data…`

Decoding of PD messages happens on the Rust side and hasn't been traced.

---

## 6. Session sequence (as the vendor app does it)

```
open HID (VID 2E3C, PID AF03, serial)
→ 0x00 handshake(timestamp)   ← 0x00 reply (check status, verify SIG)
→ 0xFF "ready"+SIG            ← 0x20 frames start (~167/s)
→ 0x02 sampling on/interval   (optional)
→ 0x03 protocol sniff on      (optional) ← 0x30 frames
→ 0x06 heartbeat, periodically
...
→ 0x03 off (if enabled), 0x05 disconnect   → stream stops
close
```

---

## 7. Firmware update (not mapped)

* RK-X3SE: HID IAP protocol (`firmware_iap.rs`: find, erase, write, verify, finish).
* Other models: after `0x04`, the device mounts as a mass-storage drive labelled
  `KX3IAP`. The app copies the `.bin` file to it and waits for `Ready.TXT`.

---

## 8. Where things live in `app.exe`

| Address | Role |
|---|---|
| `0x1400D17E0` | Frame builder (header, len, cmd, payload, CRC) |
| `0x1400D16F0` | CRC-16 |
| `0x140256490` | `connect_and_claim_device` (handshake + claim) |
| `0x140258710` / `0x140258ED0` | connect / disconnect HID |
| `0x140259140` / `0x140259750` | raw write / raw read (65 B) |
| `0x14005B6F0` | Heartbeat send (`0x06 01`) |
| `0x1405F7650` | Reader dispatch (`0x20` real-time, `0x30` protocol) |
| `0x1400D1E80` | `0x20` frame → RegularData (temperature, phone power) |
| JS `bh()`, `dj()`, `qX` class | Frontend frame builder, CRC, device service |
