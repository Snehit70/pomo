"""Minimal RFC6455 client. Stdlib only — no websockets package."""

from __future__ import annotations

import base64
import hashlib
import os
import select
import socket
import struct
import time

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
MAX_FRAME = 1024 * 1024
# _decode_frames caps one frame; MAX_MESSAGE caps a whole reassembled
# fragmented message so continuation frames cannot grow _frag_payload
# without bound.
MAX_MESSAGE = 1024 * 1024


class WebSocketError(Exception):
    pass


def _accept_key(key):
    digest = hashlib.sha1((key + GUID).encode("ascii")).digest()
    return base64.b64encode(digest).decode("ascii")


def encode_frame(payload, opcode=1):
    if isinstance(payload, str):
        payload = payload.encode("utf-8")
    header = bytearray()
    header.append(0x80 | (opcode & 0x0F))
    length = len(payload)
    mask_bit = 0x80
    if length < 126:
        header.append(mask_bit | length)
    elif length < 65536:
        header.append(mask_bit | 126)
        header.extend(struct.pack("!H", length))
    else:
        header.append(mask_bit | 127)
        header.extend(struct.pack("!Q", length))
    key = os.urandom(4)
    header.extend(key)
    masked = bytes(b ^ key[i % 4] for i, b in enumerate(payload))
    return bytes(header) + masked


def _decode_frames(buf):
    """Return (frames, rest, pings, close).

    frames is a list of (opcode, fin, payload) for data frames only.
    Control frames: pings returned separately, pongs tracked as activity,
    close aborts processing of anything after it.
    """
    frames = []
    pings = []
    pong = False
    close = False
    i = 0
    while True:
        if len(buf) - i < 2:
            break
        b0 = buf[i]
        b1 = buf[i + 1]
        opcode = b0 & 0x0F
        fin = (b0 & 0x80) != 0
        masked = (b1 & 0x80) != 0
        length = b1 & 0x7F
        header_len = 2
        if length == 126:
            if len(buf) - i < 4:
                break
            length = struct.unpack("!H", buf[i + 2 : i + 4])[0]
            header_len = 4
        elif length == 127:
            if len(buf) - i < 10:
                break
            length = struct.unpack("!Q", buf[i + 2 : i + 10])[0]
            header_len = 10
        if length > MAX_FRAME:
            raise WebSocketError("frame too large")
        mask_len = 4 if masked else 0
        total = header_len + mask_len + length
        if len(buf) - i < total:
            break
        start = i + header_len
        if masked:
            key = buf[start : start + 4]
            start += 4
            payload = bytes(buf[start + j] ^ key[j % 4] for j in range(length))
        else:
            payload = bytes(buf[start : start + length])
        i += total
        if opcode == 0x8:
            close = True
            break
        if opcode == 0x9:
            pings.append(payload)
            continue
        if opcode == 0xA:
            pong = True
            continue
        if opcode in (0x1, 0x2, 0x0):
            frames.append((opcode, fin, payload))
            continue
        # Unknown data opcode: ignore.
    return frames, buf[i:], pings, pong, close


class Rfc6455Client:
    SEND_TIMEOUT_S = 5.0
    # Tick-path (select-loop) send budget. The loop must never block >~50ms;
    # _send_all (5s, blocking) is worker-thread only (connect/hello path).
    SEND_NOWAIT_TIMEOUT_S = 0.05

    def __init__(self):
        self.sock = None
        self.buffer = bytearray()
        self.connected = False
        self.host = ""
        self.port = 0
        # RFC6455 fragment reassembly: a continuation (opcode 0) without a
        # started fragment is dropped, never parsed as standalone text.
        self._frag_opcode = None
        self._frag_payload = bytearray()
        # Monotonic timestamp of the last ping/pong/data seen from the peer.
        # 0 until the first activity; the client uses it as socket-contact
        # evidence (a paused phone sends no data frames, only pong answers).
        self.last_peer_activity_mono = 0.0

    def fileno(self):
        return self.sock.fileno() if self.sock is not None else -1

    def close(self):
        self.connected = False
        sock = self.sock
        self.sock = None
        self.buffer = bytearray()
        self._frag_opcode = None
        self._frag_payload = bytearray()
        self.last_peer_activity_mono = 0.0
        if sock is not None:
            try:
                sock.settimeout(1.0)
                sock.sendall(encode_frame(b"", opcode=0x8))
            except OSError:
                pass
            try:
                sock.close()
            except OSError:
                pass

    def connect(self, host, port, path="/ws", timeout=5.0):
        self.close()
        self.host = host
        self.port = int(port)
        sock = socket.create_connection((host, int(port)), timeout=timeout)
        sock.settimeout(timeout)
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        host_header = host if ":" not in host else "[%s]" % host
        request = (
            "GET %s HTTP/1.1\r\n"
            "Host: %s:%d\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            "Sec-WebSocket-Key: %s\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            "\r\n"
        ) % (path, host_header, int(port), key)
        try:
            sock.sendall(request.encode("ascii"))
        except OSError:
            sock.close()
            raise
        data = b""
        deadline = time.monotonic() + timeout
        while b"\r\n\r\n" not in data:
            if time.monotonic() > deadline:
                sock.close()
                raise WebSocketError("handshake timeout")
            chunk = sock.recv(4096)
            if not chunk:
                sock.close()
                raise WebSocketError("handshake closed")
            data += chunk
            if len(data) > 65536:
                sock.close()
                raise WebSocketError("handshake too large")
        header, rest = data.split(b"\r\n\r\n", 1)
        status_line = header.split(b"\r\n", 1)[0].decode("ascii", "replace")
        if " 101 " not in status_line:
            sock.close()
            raise WebSocketError("expected 101, got %s" % status_line)
        accept = None
        for line in header.split(b"\r\n")[1:]:
            if line.lower().startswith(b"sec-websocket-accept:"):
                accept = line.split(b":", 1)[1].strip().decode("ascii")
        if accept != _accept_key(key):
            sock.close()
            raise WebSocketError("bad Sec-WebSocket-Accept")
        sock.setblocking(False)
        self.sock = sock
        self.buffer = bytearray(rest)
        self.connected = True
        return True

    def _send_all(self, frame):
        """Blocking send with a hard timeout — worker-thread only.

        Used by the WS handshake/hello path running on RestWorker. Never
        call from the select loop (client.tick_ws_ping / pump_websocket):
        a stalled peer would freeze the engine for SEND_TIMEOUT_S. Tick
        code must use try_send_ping()/try_send_text() (_send_nowait)."""
        if not self.connected or self.sock is None:
            raise WebSocketError("not connected")
        try:
            self.sock.settimeout(self.SEND_TIMEOUT_S)
            self.sock.sendall(frame)
        except socket.timeout as exc:
            self._teardown_socket()
            raise WebSocketError("send timeout") from exc
        except OSError as exc:
            self._teardown_socket()
            raise WebSocketError("send failed") from exc
        finally:
            if self.sock is not None:
                try:
                    self.sock.settimeout(0.0)
                except OSError:
                    pass

    def _send_nowait(self, frame):
        """Non-blocking send for the select-loop tick path.

        Sends on the (already non-blocking) socket, waiting at most
        SEND_NOWAIT_TIMEOUT_S for writability. Maps WouldBlock/timeout to
        WebSocketError so callers only handle one exception type. Tears
        the socket down on hard errors. Never raises raw OSError into the
        loop; unexpected exceptions are wrapped as WebSocketError."""
        if not self.connected or self.sock is None:
            raise WebSocketError("not connected")
        sock = self.sock
        try:
            sock.settimeout(0.0)
        except OSError as exc:
            self._teardown_socket()
            raise WebSocketError("send failed") from exc
        deadline = time.monotonic() + self.SEND_NOWAIT_TIMEOUT_S
        view = memoryview(bytes(frame))
        while len(view) > 0:
            try:
                sent = sock.send(view)
            except BlockingIOError as exc:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise WebSocketError("send would block") from exc
                try:
                    _, writable, _ = select.select([], [sock], [], remaining)
                except (OSError, ValueError) as sel_exc:
                    self._teardown_socket()
                    raise WebSocketError("send failed") from sel_exc
                except Exception as sel_exc:  # never leak into select loop
                    self._teardown_socket()
                    raise WebSocketError("send failed") from sel_exc
                if not writable:
                    raise WebSocketError("send would block") from exc
                continue
            except socket.timeout as exc:
                self._teardown_socket()
                raise WebSocketError("send would block") from exc
            except OSError as exc:
                self._teardown_socket()
                raise WebSocketError("send failed") from exc
            except Exception as exc:  # never leak raw errors into the loop
                self._teardown_socket()
                raise WebSocketError("send failed") from exc
            if sent == 0:
                self._teardown_socket()
                raise WebSocketError("send failed")
            view = view[sent:]
        return True

    def _teardown_socket(self):
        self.connected = False
        sock = self.sock
        self.sock = None
        self.buffer = bytearray()
        self._frag_opcode = None
        self._frag_payload = bytearray()
        self.last_peer_activity_mono = 0.0
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass

    def send_text(self, text):
        # Tick-path safe: select-loop callers (hello in _apply_connect_result,
        # command sends) must never block 5s. Delegates to non-blocking
        # _send_nowait; raises only WebSocketError. _send_all is retained
        # for worker-thread use only.
        self.try_send_text(text)

    def send_ping(self):
        # Same non-blocking guarantee as send_text: tick_ws_ping runs on
        # the select loop, so a stalled peer must surface as WouldBlock
        # (WebSocketError), never a 5s stall.
        self.try_send_ping()

    def try_send_text(self, text):
        """Non-blocking text send for the select loop. Raises only
        WebSocketError (including send-would-block); never blocks >~50ms."""
        try:
            frame = encode_frame(text, opcode=1)
        except WebSocketError:
            raise
        except Exception as exc:
            raise WebSocketError("encode failed") from exc
        self._send_nowait(frame)

    def try_send_ping(self):
        """Non-blocking ping for client.tick_ws_ping. Raises only
        WebSocketError; never blocks >~50ms. Client owner: call this (or
        send_ping, now also non-blocking) from the select loop."""
        try:
            frame = encode_frame(b"", opcode=0x9)
        except WebSocketError:
            raise
        except Exception as exc:
            raise WebSocketError("encode failed") from exc
        self._send_nowait(frame)

    def send_pong(self, payload=b""):
        if not self.connected or self.sock is None:
            return
        try:
            self._send_nowait(encode_frame(payload, opcode=0xA))
        except (WebSocketError, OSError):
            # Pong is best-effort; a dead send path is handled by the
            # next recv/ping cycle.
            pass
        except Exception:
            # Never leak unexpected errors from the read/pong path into
            # the select loop.
            pass

    def recv_ready(self, timeout=0.0):
        if not self.connected or self.sock is None:
            return False
        try:
            r, _, _ = select.select([self.sock], [], [], timeout)
            return bool(r)
        except (OSError, ValueError):
            return False

    def _assemble(self, frames):
        """Assemble (opcode, fin, payload) data frames into text messages."""
        texts = []
        for opcode, fin, payload in frames:
            if opcode in (0x1, 0x2):
                if fin:
                    texts.append(payload.decode("utf-8", "replace"))
                else:
                    self._frag_opcode = opcode
                    self._frag_payload = bytearray(payload)
            elif opcode == 0x0:
                if self._frag_opcode is None:
                    # Stray continuation: protocol violation, drop silently.
                    continue
                if len(self._frag_payload) + len(payload) > MAX_MESSAGE:
                    # Peer can extend a fragmented message forever; the phone
                    # never sends fragments at all, so treat oversize as a
                    # dead stream.
                    self._teardown_socket()
                    raise WebSocketError("fragmented message too large")
                self._frag_payload.extend(payload)
                if fin:
                    if self._frag_opcode == 0x1:
                        texts.append(bytes(self._frag_payload).decode("utf-8", "replace"))
                    self._frag_opcode = None
                    self._frag_payload = bytearray()
        return texts

    def read_texts(self):
        """Read available frames. Returns list of text strings. Empty list if none.

        Raises WebSocketError on close/error. Pongs, pings and data frames
        all refresh last_peer_activity_mono.
        """
        if not self.connected or self.sock is None:
            raise WebSocketError("not connected")
        while True:
            try:
                chunk = self.sock.recv(65536)
            except BlockingIOError:
                break
            except OSError as exc:
                self._teardown_socket()
                raise WebSocketError("recv failed") from exc
            if not chunk:
                self._teardown_socket()
                raise WebSocketError("socket closed")
            self.buffer.extend(chunk)
            if len(chunk) < 65536:
                break
        try:
            frames, rest, pings, pong, close = _decode_frames(self.buffer)
        except WebSocketError:
            self._teardown_socket()
            raise
        self.buffer = bytearray(rest)
        if pings or pong or frames:
            self.last_peer_activity_mono = time.monotonic()
        for payload in pings:
            self.send_pong(payload)
        if not self.connected:
            # A failed pong tore the socket down mid-read; raise so the
            # pump's error path reconnects now instead of at the stale
            # watchdog.
            raise WebSocketError("pong send failed")
        if close:
            self._teardown_socket()
            raise WebSocketError("peer closed")
        return self._assemble(frames)
