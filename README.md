# RykenX3

Tools for the **RYKEN RK-X3 USB power meter**: a reverse-engineered protocol
description, a browser dashboard/logger, and Python tools.

**Web app:** https://ohaiibuzzle.github.io/rykenx3/

## Contents

| Path | What it is |
|---|---|
| [`PROTOCOL.md`](PROTOCOL.md) | USB HID protocol: frame format, CRC, handshake, commands, data frames |
| [`web/`](web/) | Browser app (WebHID): live readings, charts, session stats, always-on CSV log |
| [`python/`](python/) | `ryken.py` (CLI logger and log reader) and `dashboard.py` (terminal dashboard) |

## Web app

Open the link above in **Chrome, Edge or Opera on a desktop computer**, plug in the
meter and click **Connect meter**. Everything runs locally in the browser.

- Every session is logged automatically from the moment you connect; **Export CSV** saves it
  and **New session** starts over. The log is also kept in the browser, so after a crash or
  reload the page offers to restore or export the unsaved session.
- **Open CSV…** loads an exported log back into the charts and stats for review.

To run it locally (WebHID needs `https://` or `localhost`):

```sh
python3 -m http.server -d web 8000   # then open http://localhost:8000
```

## Python tools

```sh
python3 -m venv .venv && source .venv/bin/activate
pip install -r python/requirements.txt

python python/ryken.py info                   # device info, doesn't claim the meter
python python/ryken.py log -o run.csv         # live readout + CSV (Ctrl-C to stop)
python python/ryken.py read run.csv --rows 10 # summarise a log
python python/dashboard.py                    # terminal dashboard
```

Only one program can use the meter at a time. Close the vendor app, the web page or
the other script first.

## Device

| | |
|---|---|
| USB ID | `2E3C:AF03` (HID, vendor usage page) |
| Tested | RK-X3, hardware V1.3, firmware V1.22, macOS |

## License

[GNU General Public License v3.0](LICENSE). You may use, modify and redistribute this
code, but derivative works must also be released under GPLv3.
