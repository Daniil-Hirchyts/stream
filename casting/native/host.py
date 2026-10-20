"""Chrome native-messaging host. Only a fixed set of ADB player commands is exposed.

No HTTP server, media relay, screen capture, cookie access or URL logging.
"""
import ipaddress
import json
import os
from pathlib import Path
import shlex
import struct
import subprocess
import sys
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "tv.daniil.cast"
COMPONENT = PACKAGE + "/.PlayerActivity"
MAX_MESSAGE = 65536


def shell_quote(value):
    return shlex.quote(str(value))


def read_exact(size):
    chunks = bytearray()
    while len(chunks) < size:
        chunk = sys.stdin.buffer.read(size - len(chunks))
        if not chunk:
            raise EOFError
        chunks.extend(chunk)
    return bytes(chunks)


def read_message():
    size = struct.unpack("=I", read_exact(4))[0]
    if not 0 < size <= MAX_MESSAGE:
        raise ValueError("Message too large")
    return json.loads(read_exact(size))


def send_message(message):
    encoded = json.dumps(message, ensure_ascii=False).encode("utf-8")
    sys.stdout.buffer.write(struct.pack("=I", len(encoded)) + encoded)
    sys.stdout.buffer.flush()


def checked_text(value, limit):
    if not isinstance(value, str) or len(value) > limit or "\x00" in value:
        raise ValueError("Invalid text field")
    return value


def dispatch(message):
    if not isinstance(message, dict):
        raise ValueError("Expected a command object")
    action = message.get("action")
    if action not in {"info", "status", "load", "play", "pause", "stop", "seek"}:
        raise ValueError("Unsupported player command")
    config = json.loads((Path(__file__).parent / "config.json").read_text())
    host = str(ipaddress.ip_address(config["tv_host"]))
    adb = Path(config.get("adb_path", str(Path.home() / "Library/Android/sdk/platform-tools/adb")))
    if not adb.is_file():
        raise RuntimeError("Android platform-tools adb is missing. Set adb_path in casting/native/config.json.")
    environment = dict(os.environ)
    environment["ADB_VENDOR_KEYS"] = str(ROOT / "cleanyourtv/tools/adbkey/adbkey")
    serial = host + ":5555"

    def adb_call(*arguments):
        result = subprocess.run([str(adb), *arguments], env=environment,
                                capture_output=True, text=True, timeout=25)
        if result.returncode:
            raise RuntimeError("ADB could not reach the stick. Check Wi-Fi and ADB debugging, then accept its authorization prompt if shown.")
        return result.stdout.strip()

    devices = adb_call("devices")
    if serial + "\tdevice" not in devices:
        adb_call("connect", serial)

    def shell(command):
        return adb_call("-s", serial, "shell", command)

    return execute_player(message, action, host, shell)


def execute_player(message, action, host, shell):
    if action == "info":
        installed = bool(shell("pm path " + PACKAGE))
        return {"ok": True, "host": host, "installed": installed,
                "name": "Daniil’s Fire TV", "model": shell("getprop ro.product.model")}
    if action == "status":
        raw = shell("run-as " + PACKAGE + " cat files/status.json" )
        try:
            status = json.loads(raw)
        except (ValueError, TypeError):
            status = {"state": "idle", "playing": False, "position": 0, "duration": 0,
                      "title": "", "error": ""}
        return {"ok": True, "status": status}

    command = "am start -n " + COMPONENT + " --es command " + action
    if action == "load":
        url = checked_text(message.get("url"), 24000)
        parsed = urlsplit(url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise ValueError("Choose a direct HTTP(S) video or playlist URL; blob URLs only exist in the browser")
        title = checked_text(message.get("title", "Video from Chrome"), 250)
        mime = checked_text(message.get("mime", ""), 100)
        incoming = message.get("headers", {})
        if not isinstance(incoming, dict):
            raise ValueError("Invalid request headers")
        headers = {}
        for name, value in incoming.items():
            if name.lower() in {"referer", "origin", "user-agent"}:
                value = checked_text(value, 4000)
                if "\r" in value or "\n" in value:
                    raise ValueError("Invalid request header")
                headers[name] = value
        command += " --es url " + shell_quote(url) + " --es title " + shell_quote(title)
        command += " --es mime " + shell_quote(mime)
        command += " --es headers " + shell_quote(json.dumps(headers))
    if action in {"seek", "load"}:
        position = message.get("position", 0)
        if isinstance(position, bool) or not isinstance(position, (int, float)) or not 0 <= position <= 31536000000:
            raise ValueError("Invalid playback position")
        command += " --el position " + str(int(position))
    result = shell(command)
    if "Error:" in result or "Exception" in result:
        # Don't return am's echoed intent: it may contain a signed stream URL.
        raise RuntimeError("Fire TV could not start the player. Check that Daniil Cast is installed.")
    return {"ok": True, "action": action}


def main():
    while True:
        try:
            message = read_message()
        except EOFError:
            return
        except Exception:
            send_message({"ok": False, "error": "Invalid native message"})
            return
        try:
            send_message(dispatch(message))
        except (ValueError, RuntimeError) as exception:
            send_message({"ok": False, "error": str(exception)})
        except Exception as exception:
            detail = type(exception).__name__
            if isinstance(exception, OSError) and exception.errno is not None:
                detail += f" / errno {exception.errno}"
            send_message({"ok": False, "error": "Can’t reach Fire TV. Check its power, Wi-Fi and ADB debugging, and accept its authorization prompt if shown. (" + detail + ")"})


if __name__ == "__main__":
    main()
