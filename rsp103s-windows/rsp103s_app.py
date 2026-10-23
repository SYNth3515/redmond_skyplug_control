#!/usr/bin/env python3
"""
Redmond RSP-103S - приложение для Windows (BLE напрямую, без телефона).

Возможности: вкл/выкл, блокировка кнопки, статус, синхронизация часов, календарь событий
(расписание внутри розетки) с наглядным календарём на месяц, добавление/удаление событий.

Запуск:  run_app.bat   (или:  pip install bleak  и  python rsp103s_app.py)
Токен берётся из rsp103s_token.hex рядом с программой (тот же файл, что у скриптов).
"""
import asyncio
import calendar
import json
import queue
import re
import secrets
import struct
import sys
import threading
import time
import uuid
from collections import namedtuple
from datetime import date, datetime, time as dtime, timedelta, timezone
from pathlib import Path

try:
    import tkinter as tk
    from tkinter import messagebox, ttk
except ImportError:          # нужно только для тестов логики без Tk
    tk = None
from bleak import BleakClient, BleakScanner

SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
APP_DIR = Path(sys.executable).parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parent
TOKEN_FILE = APP_DIR / "rsp103s_token.hex"
CFG_FILE = APP_DIR / "rsp103s_app.json"
DAYS_RU = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"]
MONTHS_RU = ["Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август",
             "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"]
GREEN, RED = "#2E7D32", "#C62828"

# type: 0 один раз, 1 каждый день, 2 по дням недели, 3 раз в месяц; action: 3 вкл, 4 выкл
# rules: маска дней в UTC (бит 0 = понедельник)
Ev = namedtuple("Ev", "uid epoch enabled typ rules action")


class RspError(Exception):
    pass


# ---------------------------------------------------------------- розетки / настройки
def new_token() -> str:
    return secrets.token_bytes(8).hex()


def parse_hex(s: str):
    s = s.strip().lower()
    if len(s) != 16 or any(c not in "0123456789abcdef" for c in s):
        return None
    return bytes.fromhex(s)


def load_cfg() -> dict:
    try:
        return json.loads(CFG_FILE.read_text(encoding="utf-8"))
    except Exception:
        return {}


def save_cfg(cfg: dict):
    CFG_FILE.write_text(json.dumps(cfg, ensure_ascii=False, indent=1), encoding="utf-8")


def load_sockets(cfg: dict) -> list:
    """Список розеток [{id, name, address, token}]. Подхватывает настройки старой версии с одной розеткой."""
    if cfg.get("sockets"):
        return cfg["sockets"]
    if cfg.get("address") and TOKEN_FILE.exists():
        return [{"id": uuid.uuid4().hex, "name": "Розетка 1", "address": cfg["address"].upper(),
                 "token": TOKEN_FILE.read_text().strip().lower()}]
    return []


# ---------------------------------------------------------------- время и календарь (чистая логика)
def next_local(h, m, mask=0b1111111):
    """Ближайший момент HH:MM по местному времени в будущем, попадающий на выбранный день недели."""
    now = datetime.now().astimezone()
    d = now.date()
    for _ in range(9):
        c = datetime.combine(d, dtime(h, m)).astimezone()
        if c > now and mask >> c.weekday() & 1:
            return c
        d += timedelta(days=1)
    raise RspError("не нашлось подходящего дня")


def utc_mask(mask, c):
    """Розетка хранит UTC: если местное время попадает на другие сутки по UTC, сдвигаем дни."""
    shift = (c.astimezone(timezone.utc).date() - c.date()).days
    r = 0
    for d in range(7):
        if mask >> d & 1:
            r |= 1 << ((d + shift) % 7)
    return r, shift


def marks_for(evs, year, month):
    """{дата: [(время, действие, uid)]} - когда события срабатывают по местному времени."""
    out = {}
    first = date(year, month, 1)
    last = date(year, month, calendar.monthrange(year, month)[1])
    for e in evs:
        if not e.enabled:
            continue
        fu = datetime.fromtimestamp(e.epoch, timezone.utc)
        d = first - timedelta(days=2)
        while d <= last + timedelta(days=2):
            ts = datetime.combine(d, fu.timetz()).timestamp()
            hit = ts >= e.epoch and (
                ts == e.epoch if e.typ == 0 else
                True if e.typ == 1 else
                bool(e.rules >> d.weekday() & 1) if e.typ == 2 else
                d.day == fu.day if e.typ == 3 else False)
            if hit:
                loc = datetime.fromtimestamp(ts)
                if loc.year == year and loc.month == month:
                    out.setdefault(loc.date(), []).append((loc.time().replace(second=0), e.action, e.uid))
            d += timedelta(days=1)
    return {k: sorted(v) for k, v in out.items()}


def repeat_text(e: Ev) -> str:
    return {0: "один раз", 1: "каждый день", 3: "каждый месяц"}.get(
        e.typ, "по дням: " + ",".join(n for i, n in enumerate(DAYS_RU) if e.rules >> i & 1))


# ---------------------------------------------------------------- протокол розетки
def parse_ev(p: bytes) -> Ev:
    return Ev(p[4], struct.unpack("<I", p[:4])[0], bool(p[5] & 1), p[5] >> 1, p[6], p[9])


def build_ev(epoch, typ, action, rules=0) -> bytes:
    return struct.pack("<I", epoch) + bytes([0, 1 | typ << 1, rules, 0, 0, action, 1, 0, 0, 0, 0, 0])


class Rsp:
    """Одна BLE-сессия: подключиться -> авторизоваться -> команды -> отключиться."""

    def __init__(self, token: bytes):
        self.token, self.client, self.pid = token, None, 0
        self.waiters, self.buf, self.state = {}, bytearray(), None

    def _on(self, _h, data):
        self.buf += data
        while True:
            s = self.buf.find(0x55)
            if s < 0:
                self.buf.clear()
                return
            e = self.buf.find(0xAA, s + 3)
            if e < 0:
                return
            frame = bytes(self.buf[s:e + 1])
            del self.buf[:e + 1]
            if frame[2] == 0x06:
                self.state = frame[3:-1]
            fut = self.waiters.pop((frame[1], frame[2]), None)
            if fut and not fut.done():
                fut.set_result(frame)

    async def open(self, address: str) -> bool:
        dev = await BleakScanner.find_device_by_address(address, timeout=10.0)
        if dev is None:
            raise RspError("розетка не видна: закройте другие приложения/Home Assistant, поднесите ближе")
        self.client = BleakClient(dev, timeout=15.0)
        await self.client.connect()
        await self.client.start_notify(TX, self._on)
        r = await self.call(0xFF, self.token, required=False)
        return bool(r and r[0] != 0)          # любой ненулевой статус = токен принят

    async def close(self):
        try:
            if self.client and self.client.is_connected:
                await self.client.disconnect()
        except Exception:
            pass

    async def call(self, cmd, data=b"", timeout=4.0, required=True):
        pid = self.pid
        self.pid = 0 if self.pid >= 100 else self.pid + 1
        fut = asyncio.get_running_loop().create_future()
        self.waiters[(pid, cmd)] = fut
        await self.client.write_gatt_char(RX, bytes([0x55, pid, cmd]) + data + b"\xaa", response=False)
        try:
            frame = await asyncio.wait_for(fut, timeout)
        except asyncio.TimeoutError:
            self.waiters.pop((pid, cmd), None)
            if required:
                raise RspError(f"нет ответа на команду 0x{cmd:02x}")
            return None
        return frame[3:-1]

    async def tx_open(self, uid):
        r = await self.call(0x76, bytes([uid, 0, 0, 0]))
        if r[0] == 15:                         # осталась открытая транзакция
            await self.call(0x77, bytes(4))
            r = await self.call(0x76, bytes([uid, 0, 0, 0]))
        if r[0] != 0:
            raise RspError(f"транзакция календаря: ошибка {r[0]}")

    async def tx_close(self):
        await self.call(0x77, bytes(4))

    async def read_calendar(self):
        i = await self.call(0x73)
        ver, mx, cur = i[0], i[1], i[2]
        dev, shift = struct.unpack("<Ii", (await self.call(0x6F))[:8])
        evs = []
        if cur:
            await self.tx_open(0)
            try:
                for uid in range(mx + 1):
                    p = await self.call(0x70, bytes([uid]))
                    if len(p) >= 15 and (p[0:4] != bytes(4) or p[5] or p[9]):
                        evs.append(parse_ev(p))
                        if len(evs) >= cur:
                            break
            finally:
                await self.tx_close()
        diff = dev - int(time.time())
        return (f"Календарь v{ver}, ёмкость {mx}, занято {cur}. Часы розетки отличаются от ПК на {diff} с, "
                f"пояс в розетке {shift / 3600:+g} ч"), evs

    async def add_event(self, epoch, typ, action, rules=0):
        await self.tx_open(1)
        try:
            r = await self.call(0x71, build_ev(epoch, typ, action, rules))
        finally:
            await self.tx_close()
        if r[1]:
            raise RspError(f"добавление: ошибка {r[1]}")
        return r[0]

    async def remove_event(self, uid):
        await self.tx_open(1)
        try:
            r = await self.call(0x74, bytes([uid]))
        finally:
            await self.tx_close()
        if r[0]:
            raise RspError(f"удаление: ошибка {r[0]}")

    async def clear_all(self):
        await self.tx_open(1)
        try:
            r = await self.call(0x72)
        finally:
            await self.tx_close()
        if r[0]:
            raise RspError(f"очистка: ошибка {r[0]}")

    async def set_time(self):
        off = int(datetime.now().astimezone().utcoffset().total_seconds())
        r = await self.call(0x6E, struct.pack("<Ii", int(time.time()), off))
        if r[0]:
            raise RspError(f"установка времени: ошибка {r[0]}")

    async def power(self, on: bool):
        return await self.call(0x03 if on else 0x04)

    async def lock(self, on: bool):
        return await self.call(0x16, bytes([1 if on else 0]))

    async def status(self):
        r = await self.call(0x06, required=False)
        if r is None:
            await asyncio.sleep(1.5)
            r = self.state
        return r


async def scan_devices(seconds=8.0):
    found = await BleakScanner.discover(timeout=seconds, return_adv=True)
    out = []
    for dev, adv in found.values():
        name = (dev.name or adv.local_name or "")
        if SERVICE in [u.lower() for u in (adv.service_uuids or [])] or any(
                k in name.upper() for k in ("R4S", "RSP", "REDMOND", "SKY")):
            out.append((adv.rssi, dev.address, name))
    return sorted(out, reverse=True)


def hx(b):
    return b.hex(" ") if b else "нет ответа"


# ---------------------------------------------------------------- интерфейс
class App(tk.Tk if tk else object):
    def __init__(self):
        super().__init__()
        self.title("Redmond RSP-103S")
        self.geometry("640x920")
        self.minsize(580, 800)
        self.cfg = load_cfg()
        self.socks = load_sockets(self.cfg)
        sel = self.cfg.get("selected")
        self.sel_id = sel if any(s["id"] == sel for s in self.socks) else (self.socks[0]["id"] if self.socks else "")
        self.events_map, self.info_map, self.busy = {}, {}, False
        today = date.today()
        self.year, self.month, self.selected = today.year, today.month, today
        self.q = queue.Queue()
        self.loop = asyncio.new_event_loop()
        threading.Thread(target=self.loop.run_forever, daemon=True).start()
        self._build()
        self.after(100, self._poll)

    # --- текущая розетка
    @property
    def cur(self):
        return next((s for s in self.socks if s["id"] == self.sel_id), None)

    @property
    def events(self):
        c = self.cur
        return self.events_map.get(c["id"], []) if c else []

    def name(self):
        c = self.cur
        return c["name"] if c else "—"

    def save(self):
        self.cfg["sockets"], self.cfg["selected"] = self.socks, self.sel_id
        save_cfg(self.cfg)

    # --- мост между потоками
    def _poll(self):
        try:
            while True:
                self.q.get_nowait()()
        except queue.Empty:
            pass
        self.after(100, self._poll)

    def ui(self, fn):
        self.q.put(fn)

    def log(self, text):
        self.ui(lambda: self.status_var.set(text))

    def set_busy(self, b):
        self.busy = b

        def apply():
            self.bar.start(12) if b else self.bar.stop()
            for w in self.btns:
                w.configure(state="disabled" if b else "normal")
            self.combo.configure(state="disabled" if b else "readonly")
        self.ui(apply)

    def run(self, coro_factory, done=None):
        if self.busy:
            return
        self.set_busy(True)
        fut = asyncio.run_coroutine_threadsafe(coro_factory(), self.loop)

        def finish(f):
            self.set_busy(False)
            try:
                res = f.result()
            except Exception as e:
                self.log(f"Ошибка: {e}")
                return
            if done:
                self.ui(lambda: done(res))
        fut.add_done_callback(finish)

    def session(self, op, done=None):
        s = self.cur
        if s is None:
            self.log("Сначала добавьте розетку кнопкой «＋ Розетка»")
            return
        tok = parse_hex(s["token"])
        if tok is None:
            self.log(f"{s['name']}: токен должен быть 16 hex-символов (см. «Настройки»)")
            return
        if not s["address"]:
            self.log(f"{s['name']}: не задан адрес (см. «Настройки»)")
            return

        async def go():
            r = Rsp(tok)
            try:
                if not await r.open(s["address"]):
                    raise RspError(f"{s['name']}: токен не принят. Нажмите «Спарить» и зажмите кнопку на розетке")
                return await op(r)
            finally:
                await r.close()
        self.run(go, done)

    # --- сборка окна
    def _build(self):
        pad = {"padx": 8, "pady": 3}
        ttk.Label(self, text="Redmond RSP-103S", font=("Segoe UI", 15, "bold")).pack(anchor="w", **pad)
        self.btns = []

        top = ttk.Frame(self)
        top.pack(fill="x", **pad)
        self.combo = ttk.Combobox(top, state="readonly", font=("Segoe UI", 11))
        self.combo.pack(side="left", expand=True, fill="x", padx=(0, 6))
        self.combo.bind("<<ComboboxSelected>>", self.on_combo)
        b = ttk.Button(top, text="＋ Розетка", command=self.add_sock_dialog)
        b.pack(side="left")
        self.btns.append(b)

        self.status_var = tk.StringVar(value="Нажмите «Обновить», чтобы прочитать календарь розетки"
                                       if self.socks else "Добавьте розетку кнопкой «＋ Розетка»")
        ttk.Label(self, textvariable=self.status_var, wraplength=610, justify="left").pack(fill="x", **pad)
        self.bar = ttk.Progressbar(self, mode="indeterminate")
        self.bar.pack(fill="x", **pad)

        def row(items):
            f = ttk.Frame(self)
            f.pack(fill="x", **pad)
            for text, cmd in items:
                b = ttk.Button(f, text=text, command=cmd)
                b.pack(side="left", expand=True, fill="x", padx=2)
                self.btns.append(b)
        row([("Обновить", self.refresh), ("Спарить", self.pair),
             ("Статус", lambda: self.session(lambda r: r.status(), lambda x: self.log(
                 f"{self.name()}: фрейм состояния (без расшифровки): {hx(x)}")))])
        row([("Включить", self.power_on),
             ("Выключить", lambda: self.session(lambda r: r.power(False), lambda x: self.log(
                 f"{self.name()}: выключено, ответ: {hx(x)}"))),
             ("Синхр. время", lambda: self.session(lambda r: r.set_time(), lambda x: self.log(
                 f"{self.name()}: время и часовой пояс обновлены")))])
        row([("Блокировка кнопки", lambda: self.session(lambda r: r.lock(True), lambda x: self.log(
            f"{self.name()}: блокировка вкл, ответ: {hx(x)}"))),
             ("Разблокировать", lambda: self.session(lambda r: r.lock(False), lambda x: self.log(
                 f"{self.name()}: блокировка выкл, ответ: {hx(x)}")))])

        head = ttk.Frame(self)
        head.pack(fill="x", **pad)
        ttk.Button(head, text="‹", width=3, command=lambda: self.shift_month(-1)).pack(side="left")
        self.month_var = tk.StringVar()
        ttk.Label(head, textvariable=self.month_var, font=("Segoe UI", 12, "bold"), anchor="center").pack(
            side="left", expand=True, fill="x")
        ttk.Button(head, text="›", width=3, command=lambda: self.shift_month(1)).pack(side="right")

        self.cv = tk.Canvas(self, height=290, bg="white", highlightthickness=1, highlightbackground="#bbb")
        self.cv.pack(fill="x", **pad)
        self.cv.bind("<Configure>", lambda e: self.draw())
        self.cv.bind("<Button-1>", self.on_click)
        leg = ttk.Frame(self)
        leg.pack(anchor="w", **pad)
        for col, txt in ((GREEN, "включение"), (RED, "выключение")):
            tk.Label(leg, text="●", fg=col).pack(side="left")
            ttk.Label(leg, text=txt + "   ").pack(side="left")

        self.day_title = tk.StringVar()
        ttk.Label(self, textvariable=self.day_title, font=("Segoe UI", 10, "bold")).pack(anchor="w", **pad)
        self.day_box = ttk.Frame(self)
        self.day_box.pack(fill="x", **pad)

        row([("Добавить событие", self.add_dialog), ("Тест +2 мин", self.test_event),
             ("Очистить всё", self.clear_all)])

        st = ttk.LabelFrame(self, text="Настройки выбранной розетки")
        st.pack(fill="x", **pad)
        self.name_var, self.addr_var, self.tok_var = tk.StringVar(), tk.StringVar(), tk.StringVar()
        for i, (txt, var) in enumerate((("Название", self.name_var), ("BLE-адрес", self.addr_var),
                                        ("Токен (16 hex)", self.tok_var))):
            ttk.Label(st, text=txt).grid(row=i, column=0, sticky="w", padx=4)
            ttk.Entry(st, textvariable=var, width=30).grid(row=i, column=1, padx=4, pady=2)
        ttk.Button(st, text="Сохранить", command=self.save_settings).grid(row=0, column=2, padx=6)
        ttk.Button(st, text="Удалить розетку", command=self.delete_socket).grid(row=1, column=2, padx=6)
        ttk.Label(self, wraplength=610, foreground="#555", text=(
            "Время в розетке хранится в UTC и не знает о переходе на зимнее/летнее время: "
            "после перехода события нужно пересоздать.")).pack(anchor="w", **pad)
        self.refresh_combo()
        self.load_fields()
        self.update_idletasks()
        self.draw()

    # --- список розеток
    def refresh_combo(self):
        self.combo["values"] = [s["name"] for s in self.socks]
        idx = next((i for i, s in enumerate(self.socks) if s["id"] == self.sel_id), None)
        if idx is None:
            self.combo.set("Нет розеток")
        else:
            self.combo.current(idx)

    def load_fields(self):
        c = self.cur or {"name": "", "address": "", "token": ""}
        self.name_var.set(c["name"])
        self.addr_var.set(c["address"])
        self.tok_var.set(c["token"])

    def select(self, sid):
        self.sel_id = sid
        self.save()
        self.refresh_combo()
        self.load_fields()
        self.draw()

    def on_combo(self, _e=None):
        i = self.combo.current()
        if 0 <= i < len(self.socks):
            self.select(self.socks[i]["id"])
            self.log(f"Выбрана «{self.name()}». Нажмите «Обновить», чтобы прочитать её календарь."
                     if self.cur["id"] not in self.events_map else f"Выбрана «{self.name()}»")

    def save_settings(self):
        c = self.cur
        if c is None:
            return
        if not self.name_var.get().strip():
            messagebox.showerror("Название", "Введите название")
            return
        if parse_hex(self.tok_var.get()) is None:
            messagebox.showerror("Токен", "Токен должен состоять из 16 hex-символов")
            return
        c["name"], c["address"] = self.name_var.get().strip(), self.addr_var.get().strip().upper()
        c["token"] = self.tok_var.get().strip().lower()
        self.save()
        self.refresh_combo()
        self.log(f"«{c['name']}»: настройки сохранены")

    def delete_socket(self):
        c = self.cur
        if c is None:
            return
        if messagebox.askyesno("Удалить", f"Удалить «{c['name']}» из приложения?\n"
                                          "Сама розетка и её расписание не изменятся."):
            self.socks.remove(c)
            self.events_map.pop(c["id"], None)
            self.info_map.pop(c["id"], None)
            self.select(self.socks[0]["id"] if self.socks else "")
            self.log("Розетка удалена из приложения")

    def default_token(self):
        if not self.socks and TOKEN_FILE.exists() and parse_hex(TOKEN_FILE.read_text()):
            return TOKEN_FILE.read_text().strip().lower()     # токен от скриптов
        return new_token()

    def add_sock_dialog(self):
        win = tk.Toplevel(self)
        win.title("Новая розетка")
        win.transient(self)
        win.grab_set()
        name = tk.StringVar(value=f"Розетка {len(self.socks) + 1}")
        addr, tok, msg = tk.StringVar(), tk.StringVar(value=self.default_token()), tk.StringVar()
        used = {s["address"] for s in self.socks}
        found = []
        f = ttk.Frame(win, padding=10)
        f.pack()
        ttk.Label(f, text="Название").grid(row=0, column=0, sticky="w")
        ttk.Entry(f, textvariable=name, width=36).grid(row=0, column=1, pady=2)
        lb = tk.Listbox(f, height=5, width=52, exportselection=False)

        def do_scan():
            msg.set("Поиск 8 секунд…")

            async def go():
                try:
                    return await scan_devices()
                except Exception as e:
                    self.log(f"Ошибка поиска: {e}")
                    return []

            def done(res):
                if not win.winfo_exists():
                    return
                found[:] = res
                lb.delete(0, "end")
                for rssi, a, n in res:
                    lb.insert("end", f"{n or 'без имени'}  {a}  {rssi} дБм" + ("  (уже добавлена)" if a in used else ""))
                msg.set("Выберите розетку из списка" if res else
                        "Ничего не найдено. Закройте другие приложения, поднесите ближе.")
            self.run(go, done)

        def pick(_e=None):
            sel = lb.curselection()
            if sel:
                a = found[sel[0]][1]
                if a in used:
                    msg.set("Эта розетка уже добавлена")
                else:
                    addr.set(a)
        lb.bind("<<ListboxSelect>>", pick)
        ttk.Button(f, text="Найти поблизости", command=do_scan).grid(row=1, column=1, sticky="w", pady=4)
        lb.grid(row=2, column=0, columnspan=2, pady=2)
        ttk.Label(f, text="BLE-адрес").grid(row=3, column=0, sticky="w")
        ttk.Entry(f, textvariable=addr, width=36).grid(row=3, column=1, pady=2)
        ttk.Label(f, text="Токен (16 hex)").grid(row=4, column=0, sticky="w")
        ttk.Entry(f, textvariable=tok, width=36).grid(row=4, column=1, pady=2)
        ttk.Label(f, wraplength=420, foreground="#555", text=(
            "Для новой розетки оставьте токен как есть и после добавления нажмите «Спарить». "
            "Если розетка уже спарена другим токеном (например, скриптом), вставьте его.")).grid(
            row=5, column=0, columnspan=2, sticky="w", pady=4)
        ttk.Label(f, textvariable=msg, foreground="#B00020").grid(row=6, column=0, columnspan=2, sticky="w")

        def save():
            a = addr.get().strip().upper()
            if not name.get().strip():
                msg.set("Введите название")
            elif not re.fullmatch(r"([0-9A-F]{2}:){5}[0-9A-F]{2}", a):
                msg.set("Неверный адрес: нужен формат AA:BB:CC:DD:EE:FF")
            elif a in used:
                msg.set("Эта розетка уже добавлена")
            elif parse_hex(tok.get()) is None:
                msg.set("Токен должен состоять из 16 hex-символов")
            else:
                s = {"id": uuid.uuid4().hex, "name": name.get().strip(), "address": a, "token": tok.get().strip().lower()}
                self.socks.append(s)
                win.destroy()
                self.select(s["id"])
                self.log(f"«{s['name']}» добавлена. Если розетка ещё не спарена с этим токеном, нажмите «Спарить» "
                         "и зажмите кнопку на розетке, затем «Обновить».")
        bf = ttk.Frame(f)
        bf.grid(row=7, column=0, columnspan=2, sticky="e", pady=4)
        ttk.Button(bf, text="Отмена", command=win.destroy).pack(side="left", padx=4)
        ttk.Button(bf, text="Добавить", command=save).pack(side="left")

    # --- действия над выбранной розеткой
    def refresh(self):
        s = self.cur

        def done(res):
            info, evs = res
            self.events_map[s["id"]], self.info_map[s["id"]] = evs, info
            self.log(f"{s['name']}: событий в розетке {len(evs)}. {info}")
            self.draw()
        self.session(lambda r: r.read_calendar(), done)

    def pair(self):
        s = self.cur
        if s is None:
            self.log("Сначала добавьте розетку")
            return
        tok, addr = parse_hex(s["token"]), s["address"]
        if tok is None or not addr:
            self.log(f"{s['name']}: нужны адрес и токен (см. «Настройки»)")
            return
        self.log(f"{s['name']}: зажмите кнопку на розетке, пока не замигает светодиод (до 3 минут)…")

        async def go():
            end = time.monotonic() + 180
            while time.monotonic() < end:
                r = Rsp(tok)
                try:
                    if await r.open(addr):
                        return True
                except Exception:
                    pass
                finally:
                    await r.close()
                await asyncio.sleep(0.5)
            return False
        self.run(go, lambda ok: self.log(f"{s['name']}: токен принят розеткой. Теперь нажмите «Обновить»." if ok
                                         else f"{s['name']}: не удалось спарить за 3 минуты."))

    def power_on(self):
        if self.cur and messagebox.askyesno("Включить", f"Включить «{self.name()}» сейчас?\nВсё, что в неё подключено "
                                                        "(например, чайник), начнёт работать."):
            self.session(lambda r: r.power(True), lambda x: self.log(f"{self.name()}: включено, ответ: {hx(x)}"))
        elif not self.cur:
            self.log("Сначала добавьте розетку кнопкой «＋ Розетка»")

    def add_event(self, epoch, typ, action, rules):
        def done(uid):
            self.log(f"{self.name()}: событие добавлено, uid={uid}")
            self.refresh_later()
        self.session(lambda r: r.add_event(epoch, typ, action, rules), done)

    def refresh_later(self):
        self.after(300, self.refresh)

    def test_event(self):
        if not self.cur:
            self.log("Сначала добавьте розетку кнопкой «＋ Розетка»")
        elif messagebox.askyesno("Тест", f"Через 2 минуты «{self.name()}» ВКЛЮЧИТСЯ один раз.\nВыньте чайник из розетки!"):
            self.add_event(int(time.time()) // 60 * 60 + 120, 0, 3, 0)

    def clear_all(self):
        if not self.cur:
            self.log("Сначала добавьте розетку кнопкой «＋ Розетка»")
        elif messagebox.askyesno("Очистить", f"Удалить ВСЕ события из розетки «{self.name()}»?"):
            self.session(lambda r: r.clear_all(), lambda _: (self.log("Календарь очищен"), self.refresh_later()))

    def delete_event(self, uid):
        if messagebox.askyesno("Удалить", f"Удалить событие uid={uid} у «{self.name()}» (все его повторы)?"):
            self.session(lambda r: r.remove_event(uid), lambda _: (self.log("Удалено"), self.refresh_later()))

    # --- календарь
    def shift_month(self, d):
        m = self.month - 1 + d
        self.year, self.month = self.year + m // 12, m % 12 + 1
        self.draw()

    def geometry_cells(self):
        w, h = max(self.cv.winfo_width(), 350), max(self.cv.winfo_height(), 200)
        hh = 24
        return w / 7, (h - hh) / 6, hh

    def draw(self):
        c = self.cv
        c.delete("all")
        self.month_var.set(f"{MONTHS_RU[self.month - 1]} {self.year}")
        cw, ch, hh = self.geometry_cells()
        for i, n in enumerate(DAYS_RU):
            c.create_text(cw * i + cw / 2, hh / 2, text=n, fill="#666", font=("Segoe UI", 9, "bold"))
        marks = marks_for(self.events, self.year, self.month)
        offset = date(self.year, self.month, 1).weekday()
        for n in range(1, calendar.monthrange(self.year, self.month)[1] + 1):
            r, col = divmod(offset + n - 1, 7)
            x0, y0 = col * cw, hh + r * ch
            d = date(self.year, self.month, n)
            c.create_rectangle(x0 + 2, y0 + 2, x0 + cw - 2, y0 + ch - 2,
                               fill="#d6e6ff" if d == self.selected else "",
                               outline="#1565C0" if d == date.today() else "#e3e3e3",
                               width=2 if d == date.today() else 1)
            c.create_text(x0 + cw / 2, y0 + ch / 2 - 6, text=str(n), font=("Segoe UI", 11))
            ms = marks.get(d, [])[:3]
            for k, (_, action, _) in enumerate(ms):
                cx = x0 + cw / 2 + (k - (len(ms) - 1) / 2) * 12
                c.create_oval(cx - 4, y0 + ch - 16, cx + 4, y0 + ch - 8, fill=GREEN if action == 3 else RED, outline="")
        self.draw_day(marks)

    def on_click(self, ev):
        cw, ch, hh = self.geometry_cells()
        if ev.y < hh:
            return
        idx = int((ev.y - hh) // ch) * 7 + int(ev.x // cw) - date(self.year, self.month, 1).weekday() + 1
        if 1 <= idx <= calendar.monthrange(self.year, self.month)[1]:
            self.selected = date(self.year, self.month, idx)
            self.draw()

    def draw_day(self, marks):
        for w in self.day_box.winfo_children():
            w.destroy()
        d = self.selected
        self.day_title.set(f"{self.name()}: события на {d:%d.%m.%Y}")
        items = marks.get(d, []) if (d.year, d.month) == (self.year, self.month) else \
            marks_for(self.events, d.year, d.month).get(d, [])
        if not items:
            ttk.Label(self.day_box, text="Нет событий").pack(anchor="w")
        for t, action, uid in items:
            ev = next((e for e in self.events if e.uid == uid), None)
            f = ttk.Frame(self.day_box)
            f.pack(fill="x")
            tk.Label(f, text="●", fg=GREEN if action == 3 else RED).pack(side="left")
            ttk.Label(f, text=f" {t:%H:%M}  {'включить' if action == 3 else 'выключить'}"
                              f"  ({repeat_text(ev) if ev else ''})").pack(side="left")
            ttk.Button(f, text="Удалить", command=lambda u=uid: self.delete_event(u)).pack(side="right")

    # --- диалог добавления события
    def add_dialog(self):
        if not self.cur:
            self.log("Сначала добавьте розетку кнопкой «＋ Розетка»")
            return
        win = tk.Toplevel(self)
        win.title(f"Новое событие: {self.name()}")
        win.transient(self)
        win.grab_set()
        hh, mm = tk.StringVar(value="05"), tk.StringVar(value="00")
        action, rep = tk.IntVar(value=3), tk.IntVar(value=1)
        days = [tk.BooleanVar(value=i < 5) for i in range(7)]
        f = ttk.Frame(win, padding=10)
        f.pack()
        t = ttk.Frame(f)
        t.pack(anchor="w")
        ttk.Label(t, text="Время: ").pack(side="left")
        ttk.Spinbox(t, from_=0, to=23, width=3, format="%02.0f", textvariable=hh, wrap=True).pack(side="left")
        ttk.Label(t, text=" : ").pack(side="left")
        ttk.Spinbox(t, from_=0, to=59, width=3, format="%02.0f", textvariable=mm, wrap=True).pack(side="left")
        for txt, v in (("Включить", 3), ("Выключить", 4)):
            ttk.Radiobutton(f, text=txt, variable=action, value=v).pack(anchor="w")
        ttk.Separator(f).pack(fill="x", pady=4)
        ttk.Radiobutton(f, text=f"Один раз ({self.selected:%d.%m.%Y}, выбрано в календаре)", variable=rep, value=0).pack(anchor="w")
        ttk.Radiobutton(f, text="Каждый день", variable=rep, value=1).pack(anchor="w")
        ttk.Radiobutton(f, text="По дням недели:", variable=rep, value=2).pack(anchor="w")
        dr = ttk.Frame(f)
        dr.pack(anchor="w")
        for i, n in enumerate(DAYS_RU):
            ttk.Checkbutton(dr, text=n, variable=days[i]).pack(side="left")
        err = tk.StringVar()
        ttk.Label(f, textvariable=err, foreground="#B00020").pack(anchor="w", pady=4)

        def save():
            try:
                h, m = int(hh.get()), int(mm.get())
                assert 0 <= h <= 23 and 0 <= m <= 59
            except Exception:
                err.set("Неверное время")
                return
            a, mask = action.get(), sum(1 << i for i, v in enumerate(days) if v.get())
            if rep.get() == 0:
                z = datetime.combine(self.selected, dtime(h, m)).astimezone()
                if z <= datetime.now().astimezone():
                    err.set("Это время уже прошло")
                    return
                args = (int(z.timestamp()), 0, a, 0)
            elif rep.get() == 1 or mask == 127:
                args = (int(next_local(h, m).timestamp()), 1, a, 0)
            elif mask == 0:
                err.set("Выберите хотя бы один день")
                return
            else:
                c = next_local(h, m, mask)
                args = (int(c.timestamp()), 2, a, utc_mask(mask, c)[0])
            win.destroy()
            self.add_event(*args)
        bf = ttk.Frame(f)
        bf.pack(anchor="e")
        ttk.Button(bf, text="Отмена", command=win.destroy).pack(side="left", padx=4)
        ttk.Button(bf, text="Сохранить", command=save).pack(side="left")


if __name__ == "__main__":
    if tk is None:
        sys.exit("Не найден tkinter. Установите Python с python.org (с галочкой tcl/tk).")
    App().mainloop()
