#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Разбор диагностических логов FogMap (fix-track-gap-visibility 6.1/6.2).

Только стандартная библиотека, без сети: запускается на чистом `py -3`.

    py -3 scripts/analyze-devlog.py <файл.jsonl | папка> [...]

Формат строки (DevLog): ts_wall|ts_mono|session|seq|tag|level|msg|payload_json

Что показывает:
  * сессии процесса с границами по wall-clock и абсолютной шкале boot_mono_ms;
  * разрывы с атрибуцией: процесс убит / заморожен / молчал провайдер;
  * ритм watchdog и heartbeat;
  * интервалы тишины Fused (no_fix) с фактической длительностью;
  * дни без записи (нет файла / нет принятых точек);
  * долю шумных событий (TRACK/day_branches) по каждому файлу.

Логи до появления канала LIFE (октябрь 2026 и раньше) разбираются тоже, но
причина разрыва в них честно помечается как неизвестная.
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
from collections import Counter, defaultdict
from datetime import datetime, timedelta

# Штатный интервал цикла записи, сек (TrackingService.FLUSH_INTERVAL_MS).
FLUSH_INTERVAL_S = 15
# Порог интервала тишины Fused, мс (SilenceGap.THRESHOLD_MS).
NO_FIX_THRESHOLD_MS = 5 * 60 * 1000
# Разрыв в событиях, который стоит печатать, сек (порог тишины Fused).
DEFAULT_GAP_S = 300
# Целевая доля шумных событий в файле (6.2).
NOISY_TARGET = 0.20
NOISY_MSG = "day_branches"


class Event:
    __slots__ = ("path", "ts_wall", "ts_mono", "session", "seq", "tag", "level", "msg", "payload")

    def __init__(self, path, ts_wall, ts_mono, session, seq, tag, level, msg, payload):
        self.path = path
        self.ts_wall = ts_wall
        self.ts_mono = ts_mono
        self.session = session
        self.seq = seq
        self.tag = tag
        self.level = level
        self.msg = msg
        self.payload = payload

    @property
    def is_life(self):
        return self.tag == "LIFE"

    def boot_mono(self):
        """Абсолютная шкала загрузки: boot_mono_ms из payload (LIFE/flush)."""
        value = self.payload.get("boot_mono_ms")
        return int(value) if isinstance(value, (int, float)) else None

    def boot_anchor(self):
        """Оценка старта процесса на шкале boot_mono (boot - ts_mono)."""
        boot = self.boot_mono()
        return boot - self.ts_mono if boot is not None else None


class Session:
    def __init__(self, sid):
        self.sid = sid
        self.events = 0
        self.first = None
        self.last = None
        self.boot_anchor = None
        self.proc_start = None
        self.exit_reason = None
        self.heartbeats = 0
        self.freeze_gaps = []
        self.start_attempts = 0
        self.watchdog = 0

    def add(self, ev):
        self.events += 1
        if self.first is None or ev.ts_wall < self.first.ts_wall:
            self.first = ev
        if self.last is None or ev.ts_wall > self.last.ts_wall:
            self.last = ev
        anchor = ev.boot_anchor()
        if anchor is not None:
            self.boot_anchor = anchor if self.boot_anchor is None else min(self.boot_anchor, anchor)
        if ev.is_life and ev.msg == "proc_start":
            self.proc_start = ev
        elif ev.is_life and ev.msg == "exit_reason":
            self.exit_reason = ev
        elif ev.is_life and ev.msg == "heartbeat":
            self.heartbeats += 1
        elif ev.is_life and ev.msg == "freeze_gap":
            self.freeze_gaps.append(ev)
        elif ev.msg == "start_attempt":
            self.start_attempts += 1
        elif ev.msg == "watchdog":
            self.watchdog += 1


def parse_line(path, line):
    parts = line.rstrip("\n").split("|", 7)
    if len(parts) != 8:
        return None
    raw_wall, raw_mono, session, raw_seq, tag, level, msg, raw_payload = parts
    try:
        ts_wall = int(raw_wall)
        ts_mono = int(raw_mono)
        seq = int(raw_seq)
    except ValueError:
        return None
    payload = {}
    if raw_payload and raw_payload != "{}":
        try:
            parsed = json.loads(raw_payload)
            if isinstance(parsed, dict):
                payload = parsed
        except json.JSONDecodeError:
            payload = {}
    return Event(path, ts_wall, ts_mono, session, seq, tag, level, msg, payload)


def collect(paths):
    events = []
    files = []
    for path in paths:
        if os.path.isdir(path):
            names = sorted(
                os.path.join(path, name)
                for name in os.listdir(path)
                if name.startswith("fog-") and name.endswith(".jsonl")
            )
        else:
            names = [path]
        for name in names:
            files.append(name)
            try:
                with open(name, "r", encoding="utf-8", errors="replace") as handle:
                    for line in handle:
                        ev = parse_line(name, line)
                        if ev is not None:
                            events.append(ev)
            except OSError as error:
                print("не прочитан %s: %s" % (name, error), file=sys.stderr)
    events.sort(key=lambda e: (e.ts_wall, e.seq))
    return events, files


def fmt_dt(ms):
    return datetime.fromtimestamp(ms / 1000.0).strftime("%Y-%m-%d %H:%M:%S")


def fmt_duration(ms):
    seconds = max(0, int(ms // 1000))
    hours, rest = divmod(seconds, 3600)
    minutes, secs = divmod(rest, 60)
    if hours:
        return "%d ч %02d мин" % (hours, minutes)
    if minutes:
        return "%d мин %02d с" % (minutes, secs)
    return "%d с" % secs


def fmt_int(value):
    return "{:,}".format(value).replace(",", " ")


def describe_exit(ev):
    payload = ev.payload
    reason = payload.get("reason") or "?"
    vendor = payload.get("vendor")
    age = payload.get("age_ms")
    text = str(reason)
    if vendor:
        text += " (%s)" % vendor
    if isinstance(age, (int, float)):
        text += ", %s назад" % fmt_duration(age)
    return text


def build_sessions(events):
    sessions = {}
    for ev in events:
        session = sessions.get(ev.session)
        if session is None:
            session = Session(ev.session)
            sessions[ev.session] = session
        session.add(ev)
    by_wall = sorted(sessions.values(), key=lambda s: s.first.ts_wall)
    if all(s.boot_anchor is not None for s in by_wall):
        by_boot = sorted(by_wall, key=lambda s: s.boot_anchor)
        # Обычный случай: абсолютная шкала и wall-clock дают один порядок.
        if by_boot == by_wall:
            return by_boot, "boot"
        # Перезагрузка (boot сбросился) или перевод часов: честный порядок —
        # wall-clock, а расхождение показывается явно.
        return by_wall, "mixed"
    return by_wall, "wall"


def print_sessions(sessions, order):
    print("\n== Сессии процесса ==")
    if not sessions:
        print("  событий нет")
        return
    if order == "boot":
        print("  порядок: абсолютная шкала boot_mono_ms")
    elif order == "mixed":
        print(
            "  (*) boot_mono_ms расходится с wall-clock (перезагрузка или смена "
            "часов): порядок по wall-clock"
        )
    else:
        print("  (*) boot_mono_ms нет (лог до канала LIFE): порядок по wall-clock")
    for index, session in enumerate(sessions, 1):
        boot = ""
        if session.boot_anchor is not None:
            boot = "  boot_anchor=%d мс" % session.boot_anchor
        print(
            "  %2d. session=%s  %s -> %s  событий=%d%s"
            % (
                index,
                session.sid[:8],
                fmt_dt(session.first.ts_wall),
                fmt_dt(session.last.ts_wall),
                session.events,
                boot,
            )
        )
        if session.proc_start is not None:
            payload = session.proc_start.payload
            gap = payload.get("gap_ms")
            prev = payload.get("prev_session")
            parts = ["proc_start"]
            if prev:
                parts.append("prev=%s" % str(prev)[:8])
            if isinstance(gap, (int, float)):
                parts.append("разрыв=%s" % fmt_duration(gap))
            print("      LIFE: " + ", ".join(parts))
        if session.exit_reason is not None:
            print("      предыдущая смерть: " + describe_exit(session.exit_reason))
        print(
            "      heartbeat=%d, freeze_gap=%d, watchdog=%d, start_attempt=%d"
            % (
                session.heartbeats,
                len(session.freeze_gaps),
                session.watchdog,
                session.start_attempts,
            )
        )


def session_gaps(sessions, gap_s):
    """Разрывы между концом одной сессии и началом следующей."""
    gaps = []
    for prev, nxt in zip(sessions, sessions[1:]):
        delta = nxt.first.ts_wall - prev.last.ts_wall
        if delta < gap_s * 1000:
            continue
        gaps.append((prev, nxt, delta))
    return gaps


def inner_gaps(events, gap_s):
    """Разрывы внутри одной сессии (цикл записи молчал)."""
    gaps = []
    for prev, nxt in zip(events, events[1:]):
        delta = nxt.ts_wall - prev.ts_wall
        if delta < gap_s * 1000:
            continue
        gaps.append((prev, nxt, delta))
    return gaps


def attribute_inner(prev, nxt, delta, freeze_after, heartbeat_inside, has_life):
    """Почему внутри сессии не было событий."""
    if freeze_after is not None:
        payload = freeze_after.payload
        source = payload.get("source", "?")
        real = payload.get("gap_ms", delta)
        return "заморозка процесса (LIFE/freeze_gap, %s, %s)" % (source, fmt_duration(real))
    if heartbeat_inside:
        return "цикл записи стоял, процесс жив (heartbeat шел все это время)"
    if has_life:
        return "процесс не работал: ни flush, ни heartbeat"
    return "причина неизвестна (лог до канала LIFE)"


def print_gaps(sessions, events, gap_s):
    print("\n== Разрывы ==")
    printed = 0

    for prev, nxt, delta in session_gaps(sessions, gap_s):
        printed += 1
        payload = nxt.proc_start.payload if nxt.proc_start is not None else {}
        gap = payload.get("gap_ms")
        detail = "процесс отсутствовал"
        if isinstance(gap, (int, float)):
            detail += " %s (proc_start.gap_ms)" % fmt_duration(gap)
        if nxt.exit_reason is not None:
            detail += "; причина: " + describe_exit(nxt.exit_reason)
        elif nxt.proc_start is not None:
            detail += "; причина не записана (exit_reason отсутствует)"
        else:
            detail += "; причина неизвестна (лог до LIFE)"
        print(
            "  %s -> %s  (%.0f мин)  session %s -> %s\n      %s"
            % (
                fmt_dt(prev.last.ts_wall),
                fmt_dt(nxt.first.ts_wall),
                delta / 60000.0,
                prev.sid[:8],
                nxt.sid[:8],
                detail,
            )
        )

    # Индексы freeze_gap для быстрой привязки к разрыву.
    freezes = [e for e in events if e.is_life and e.msg == "freeze_gap"]
    beats = [e for e in events if e.is_life and e.msg == "heartbeat"]
    life_sessions = set(e.session for e in events if e.is_life)
    for prev, nxt, delta in inner_gaps(events, gap_s):
        if prev.session != nxt.session:
            continue
        freeze = None
        for ev in freezes:
            if prev.ts_wall <= ev.ts_wall <= nxt.ts_wall + 1000:
                freeze = ev
                break
        heartbeat_inside = any(
            prev.ts_wall < ev.ts_wall < nxt.ts_wall for ev in beats
        )
        printed += 1
        print(
            "  %s -> %s  (%.0f мин)  session %s\n      %s"
            % (
                fmt_dt(prev.ts_wall),
                fmt_dt(nxt.ts_wall),
                delta / 60000.0,
                prev.session[:8],
                attribute_inner(
                    prev, nxt, delta, freeze, heartbeat_inside,
                    prev.session in life_sessions,
                ),
            )
        )

    if printed == 0:
        print("  разрывов длиннее %d с нет" % gap_s)


def within_session_gaps(items):
    """Интервалы между соседними событиями одной сессии, сек.

    Стыки сессий не считаются: там разрыв объясняется отсутствием процесса,
    а не ритмом канала.
    """
    return [
        (b.ts_wall - a.ts_wall) / 1000.0
        for a, b in zip(items, items[1:])
        if a.session == b.session
    ]


def print_watchdog(events):
    print("\n== Ритм каналов ==")
    watch = [e for e in events if e.msg == "watchdog"]
    deltas = within_session_gaps(watch)
    if deltas:
        print(
            "  watchdog: %d срабатываний, медиана %.1f мин, максимум %.1f мин"
            % (len(watch), statistics.median(deltas) / 60.0, max(deltas) / 60.0)
        )
    elif watch:
        print("  watchdog: одно срабатывание")
    else:
        print("  watchdog: событий нет")

    beats = [e for e in events if e.is_life and e.msg == "heartbeat"]
    deltas = within_session_gaps(beats)
    if deltas:
        channels = Counter(e.payload.get("via", "?") for e in beats)
        print(
            "  heartbeat: %d ударов (%s), медиана %.1f мин, максимум %.1f мин"
            % (
                len(beats),
                ", ".join("%s=%d" % item for item in channels.most_common()),
                statistics.median(deltas) / 60.0,
                max(deltas) / 60.0,
            )
        )
    else:
        if len(beats) == 1:
            print("  heartbeat: 1 удар")
        else:
            print("  heartbeat: событий нет (лог до LIFE или процесс жил меньше 5 минут)")

    flushes = [e for e in events if e.msg == "flush"]
    deltas = within_session_gaps(flushes)
    if deltas:
        print(
            "  flush: %d тиков, медиана %.1f с, максимум %.1f с (штатно ~%d с)"
            % (
                len(flushes),
                statistics.median(deltas),
                max(deltas),
                FLUSH_INTERVAL_S,
            )
        )
    elif flushes:
        print("  flush: один тик")
    else:
        print("  flush: событий нет")

    attempts = [e for e in events if e.msg == "start_attempt"]
    if attempts:
        outcomes = Counter(str(e.payload.get("outcome", "?")) for e in attempts)
        print(
            "  попытки старта: %d (%s)"
            % (len(attempts), ", ".join("%s=%d" % i for i in outcomes.most_common()))
        )


def print_silence(events):
    print("\n== Тишина Fused (no_fix) ==")
    no_fix = [e for e in events if e.msg == "no_fix"]
    if not no_fix:
        print("  событий no_fix нет")
        return
    total = 0
    for ev in no_fix:
        gap = ev.payload.get("gap_ms")
        if isinstance(gap, (int, float)):
            total += int(gap)
            print(
                "  %s  %s  (mode=%s, profile=%s)"
                % (
                    fmt_dt(ev.ts_wall),
                    fmt_duration(gap),
                    ev.payload.get("mode", "?"),
                    ev.payload.get("profile", "?"),
                )
            )
        else:
            print("  %s  gap_ms отсутствует" % fmt_dt(ev.ts_wall))
    print(
        "  итого: %d интервалов, %s; порог %.0f мин"
        % (len(no_fix), fmt_duration(total), NO_FIX_THRESHOLD_MS / 60000.0)
    )
    # Провайдер молчал, пока цикл записи тикал: flush с нулевой пачкой.
    quiet = [e for e in events if e.msg == "flush" and int(e.payload.get("batch", 0)) == 0]
    if quiet:
        print("  холостых flush (batch=0): %d" % len(quiet))


def print_days(events, files):
    print("\n== Дни ==")
    per_day = defaultdict(lambda: {"events": 0, "points": 0, "stats": []})
    for ev in events:
        day = fmt_dt(ev.ts_wall)[:10]
        bucket = per_day[day]
        bucket["events"] += 1
        if ev.msg == "flush":
            bucket["points"] += int(ev.payload.get("batch", 0))
        elif ev.msg == "day_stats":
            bucket["stats"].append(ev.payload)

    file_days = set()
    for name in files:
        base = os.path.basename(name)
        if base.startswith("fog-") and base.endswith(".jsonl"):
            stamp = base[len("fog-"):-len(".jsonl")]
            if len(stamp) == 8 and stamp.isdigit():
                file_days.add("%s-%s-%s" % (stamp[:4], stamp[4:6], stamp[6:]))

    all_days = set(per_day) | file_days
    if not all_days:
        print("  данных нет")
        return
    first = datetime.strptime(min(all_days), "%Y-%m-%d")
    last = datetime.strptime(max(all_days), "%Y-%m-%d")
    missing = []
    empty = []
    cursor = first
    while cursor <= last:
        day = cursor.strftime("%Y-%m-%d")
        info = per_day.get(day)
        if info is None:
            missing.append(day)
        elif info["points"] == 0 and not info["stats"]:
            empty.append(day)
        cursor += timedelta(days=1)

    for day in sorted(per_day):
        info = per_day[day]
        stats = info["stats"]
        line = "  %s  событий=%d, принято точек=%d" % (
            day,
            info["events"],
            info["points"],
        )
        if stats:
            last_stats = stats[-1]
            line += "  day_stats: dist=%s м, time=%s с, points=%s, flush=%s" % (
                last_stats.get("dist_cm", 0) // 100,
                last_stats.get("time_s", 0),
                last_stats.get("points", 0),
                last_stats.get("flush", 0),
            )
        print(line)

    if missing:
        print("  ДНИ БЕЗ ЗАПИСИ (нет событий и файла): " + ", ".join(missing))
    if empty:
        print("  дни без принятых точек: " + ", ".join(empty))
    if not missing and not empty:
        print("  пропусков нет")


def print_noise(files, events):
    print("\n== Шум ==")
    by_file = defaultdict(list)
    for ev in events:
        by_file[ev.path].append(ev)
    for name in files:
        items = by_file.get(name, [])
        if not items:
            continue
        noisy = sum(1 for ev in items if ev.msg == NOISY_MSG)
        share = noisy / float(len(items)) if items else 0.0
        verdict = "норм" if share <= NOISY_TARGET else "ШУМНО"
        print(
            "  %s: %d/%d %s (%.1f%%, цель <= %.0f%%) — %s"
            % (
                os.path.basename(name),
                noisy,
                len(items),
                NOISY_MSG,
                share * 100.0,
                NOISY_TARGET * 100.0,
                verdict,
            )
        )

    messages = Counter(ev.msg for ev in events)
    top = messages.most_common(8)
    print("  самые частые события: " + ", ".join("%s=%d" % item for item in top))


def main():
    parser = argparse.ArgumentParser(
        description="Разбор диагностических логов FogMap (fog-*.jsonl)."
    )
    parser.add_argument("paths", nargs="+", help="файлы fog-*.jsonl или папки с ними")
    parser.add_argument(
        "--gap-min",
        type=float,
        default=DEFAULT_GAP_S / 60.0,
        help="минимальный печатаемый разрыв, минуты (по умолчанию 2)",
    )
    args = parser.parse_args()

    events, files = collect(args.paths)
    if not events:
        print("События DevLog не найдены: " + ", ".join(args.paths))
        return 1

    print("== Файлы ==")
    for name in files:
        print("  %s" % name)
    print(
        "  всего событий: %s, окно: %s -> %s"
        % (
            fmt_int(len(events)),
            fmt_dt(events[0].ts_wall),
            fmt_dt(events[-1].ts_wall),
        )
    )

    sessions, order = build_sessions(events)
    print_sessions(sessions, order)
    print_gaps(sessions, events, int(args.gap_min * 60))
    print_watchdog(events)
    print_silence(events)
    print_days(events, files)
    print_noise(files, events)
    return 0


if __name__ == "__main__":
    sys.exit(main())
