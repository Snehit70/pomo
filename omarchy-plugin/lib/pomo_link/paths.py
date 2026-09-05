"""Default socket, status, pairing, and systemd paths for pomo-link."""

from __future__ import annotations

import os


def home():
    return os.environ.get("HOME") or os.path.expanduser("~")


def socket_path():
    override = os.environ.get("POMO_LINK_SOCKET")
    if override:
        return override
    runtime = os.environ.get("XDG_RUNTIME_DIR")
    if runtime:
        return os.path.join(runtime, "pomo", "pomo-link.sock")
    # noqa: S108 -- stable per-uid fallback when XDG_RUNTIME_DIR is unset;
    # ensure_socket_dir() requires user-owned 0700 before bind.
    return os.path.join("/tmp", "pomo-%s" % os.getuid(), "pomo-link.sock")


def ensure_socket_dir(sock_path):
    """Require a user-owned, non-symlink 0700 parent dir before IPC bind."""
    import stat

    directory = os.path.dirname(sock_path)
    if not directory:
        return
    try:
        st = os.lstat(directory)
    except FileNotFoundError:
        os.makedirs(directory, mode=0o700, exist_ok=True)
        os.chmod(directory, 0o700)
        return
    if stat.S_ISLNK(st.st_mode):
        raise OSError("refusing IPC dir symlink: %s" % directory)
    if not stat.S_ISDIR(st.st_mode):
        raise OSError("IPC parent is not a directory: %s" % directory)
    if st.st_uid != os.getuid():
        raise OSError("IPC dir not owned by current user: %s" % directory)
    if stat.S_IMODE(st.st_mode) != 0o700:
        os.chmod(directory, 0o700)


def status_path():
    override = os.environ.get("POMO_LINK_STATUS")
    if override:
        return override
    xdg = os.environ.get("XDG_STATE_HOME")
    if xdg:
        return os.path.join(xdg, "pomo", "waybar.json")
    return os.path.join(home(), ".local", "state", "pomo", "waybar.json")


def desktop_client_config_path():
    override = os.environ.get("POMO_DESKTOP_CLIENT_CONFIG")
    if override:
        return override
    xdg = os.environ.get("XDG_CONFIG_HOME")
    if xdg:
        return os.path.join(xdg, "pomo", "desktop-client.json")
    return os.path.join(home(), ".config", "pomo", "desktop-client.json")


def systemd_user_dir():
    xdg = os.environ.get("XDG_CONFIG_HOME")
    if xdg:
        return os.path.join(xdg, "systemd", "user")
    return os.path.join(home(), ".config", "systemd", "user")


def systemd_unit_path():
    return os.path.join(systemd_user_dir(), "pomo-link.service")


def local_bin_dir():
    return os.path.join(home(), ".local", "bin")


def ensure_data_dir_lock(directory):
    """Ensure `directory` exists (0700) and take an inter-process data-dir lock.

    Client owner wiring (not done here): call once at startup before any
    state read/write and keep the returned object alive for the process
    lifetime, e.g.::

        _data_lock = ensure_data_dir_lock(data_dir)
        if _data_lock is None:
            # Another instance holds the dir; run read-only or exit.

    Contract:
    - Stdlib only, import-safe (``fcntl`` is imported lazily so module
      import never fails where it is unavailable).
    - Never raises: missing dir is created 0700; any failure to create
      or lock returns ``None`` instead of raising.
    - Returns an open binary file object holding ``<dir>/.lock`` on
      success (caller must keep a reference; closing releases the
      flock-held lock), or ``None`` when another live instance holds it
      or the lock cannot be acquired.
    - Primary mechanism is ``fcntl.flock(LOCK_EX | LOCK_NB)`` on
      ``<dir>/.lock`` (kernel-released on crash, no stale files). The
      PID is written best-effort for observability only.
    - Fallback when ``fcntl`` is unavailable: ``O_CREAT | O_EXCL`` PID
      file with stale-lock takeover — an existing lock is unlinked and
      retried only if its PID is dead (``os.kill(pid, 0)``) or its mtime
      is older than 120s; a live PID means ``None`` (held).
    """
    import time

    if not directory:
        return None
    try:
        os.makedirs(directory, mode=0o700, exist_ok=True)
    except OSError:
        return None
    try:
        os.chmod(directory, 0o700)
    except OSError:
        pass
    lock_path = os.path.join(directory, ".lock")

    try:
        import fcntl
    except ImportError:
        fcntl = None  # type: ignore[assignment]
    if fcntl is not None:
        try:
            fh = open(lock_path, "a+b")
        except OSError:
            return None
        try:
            fcntl.flock(fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except (OSError, IOError):
            try:
                fh.close()
            except OSError:
                pass
            return None
        try:
            fh.seek(0)
            fh.truncate(0)
            fh.write(("%d\n" % os.getpid()).encode("ascii"))
            fh.flush()
        except OSError:
            pass
        return fh

    # --- Fallback: O_CREAT|O_EXCL PID file (no fcntl, e.g. non-Unix). ---
    stale_after_s = 120.0
    for _ in range(2):
        try:
            fd = os.open(lock_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        except FileExistsError:
            pass
        except OSError:
            return None
        else:
            try:
                os.write(fd, ("%d\n" % os.getpid()).encode("ascii"))
            except OSError:
                pass
            try:
                return os.fdopen(fd, "wb")
            except OSError:
                try:
                    os.close(fd)
                except OSError:
                    pass
                return None
        # Lock file exists: take over only if stale, else report held.
        try:
            with open(lock_path, "r") as existing:
                pid_text = (existing.read() or "").strip().split()[0]
                pid = int(pid_text)
        except (OSError, ValueError, IndexError):
            pid = 0
        alive = False
        if pid > 0:
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                alive = False
            except PermissionError:
                alive = True  # PID exists, we just cannot signal it.
            except OSError:
                alive = True  # conservative: treat as held.
        try:
            mtime = os.stat(lock_path).st_mtime
            wall_age_s = time.time() - mtime
        except OSError:
            continue  # raced with unlink; retry once.
        if alive:
            return None
        if pid <= 0 and wall_age_s < stale_after_s:
            return None  # fresh but unparsable; do not steal.
        try:
            os.unlink(lock_path)
        except OSError:
            return None
    return None
