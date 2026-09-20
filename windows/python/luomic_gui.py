"""luo mic 电脑端 · Python 图形界面（tkinter，无需额外 GUI 依赖）。

用法：
    python luomic_gui.py              # 打开界面
    python luomic_gui.py --list-devices   # 只列出音频设备
"""

from __future__ import annotations

import queue
import sys
import threading
import time
import tkinter as tk
from tkinter import messagebox, ttk
from typing import List, Optional

import luomic
from luomic import AudioOutput, Server

BG = "#0E1116"
CARD = "#171B22"
STROKE = "#262C36"
ACCENT = "#3DDC97"
WARN = "#FFB020"
TEXT = "#E9EDF2"
MUTED = "#9AA4B2"
ERR = "#FF5A5A"


class App:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        root.title("luo mic — 手机麦克风 → 电脑（Python 版）")
        root.geometry("900x640")
        root.configure(bg=BG)
        root.minsize(760, 560)

        self.log_queue: "queue.Queue[str]" = queue.Queue()
        self.ui_queue: "queue.Queue[tuple]" = queue.Queue()
        self.player = AudioOutput(self.log)
        self.server = Server(
            log=self.log,
            on_session=lambda c, n, ip: self.ui_queue.put(("session", c, n, ip)),
            on_stats=lambda t, db, s: self.ui_queue.put(("stats", t, db, s)),
            player=self.player,
        )
        self.devices: List[dict] = []
        self._build_ui()
        self.refresh_devices()
        self.log("欢迎使用 luo mic（Python 版）。手机与电脑需在同一个局域网。")
        self.log("本机局域网地址：" + "，".join(luomic.local_ipv4_list()))
        root.after(200, self._pump)
        root.protocol("WM_DELETE_WINDOW", self._on_close)

    # -------------------------------------------------- 界面

    def _build_ui(self) -> None:
        head = tk.Frame(self.root, bg=BG)
        head.pack(fill="x", padx=16, pady=(14, 6))
        tk.Label(head, text="luo mic", bg=BG, fg=TEXT,
                 font=("Segoe UI", 22, "bold")).pack(side="left")
        tk.Label(head, text="  把手机麦克风接入这台电脑 · 局域网自动发现 · 断线自动重连",
                 bg=BG, fg=MUTED, font=("Segoe UI", 10)).pack(side="left", pady=(8, 0))

        card = tk.Frame(self.root, bg=CARD, highlightbackground=STROKE, highlightthickness=1)
        card.pack(fill="x", padx=16, pady=8)
        self.state_label = self._kv(card, "服务状态", "未启动", WARN)
        self.phone_label = self._kv(card, "已连接手机", "—", TEXT)
        self.ip_label = self._kv(card, "本机 IP", "，".join(luomic.local_ipv4_list()), TEXT)
        self.device_label = self._kv(card, "音频输出", "—", TEXT)

        mid = tk.Frame(self.root, bg=BG)
        mid.pack(fill="x", padx=16, pady=6)
        tk.Label(mid, text="播放到：", bg=BG, fg=MUTED, font=("Segoe UI", 10)).pack(side="left")
        self.device_combo = ttk.Combobox(mid, state="readonly", width=52)
        self.device_combo.pack(side="left", padx=6)
        self.device_combo.bind("<<ComboboxSelected>>", lambda e: self._on_device_change())
        tk.Button(mid, text="刷新设备", command=self.refresh_devices).pack(side="left", padx=4)
        self.virtual_hint = tk.Label(mid, text="", bg=BG, fg=WARN, font=("Segoe UI", 9))
        self.virtual_hint.pack(side="left", padx=6)

        meter = tk.Frame(self.root, bg=BG)
        meter.pack(fill="x", padx=16, pady=(4, 0))
        tk.Label(meter, text="麦克风电平：", bg=BG, fg=MUTED, font=("Segoe UI", 10)).pack(side="left")
        self.canvas = tk.Canvas(meter, height=12, bg=CARD, highlightthickness=0)
        self.canvas.pack(side="left", fill="x", expand=True, padx=6)
        self.canvas.bind("<Configure>", lambda e: self._draw_level())
        self.level = 0.0

        self.stats_label = tk.Label(self.root, text=" ", bg=BG, fg=MUTED,
                                    font=("Consolas", 10), anchor="w")
        self.stats_label.pack(fill="x", padx=16, pady=(6, 0))

        box = tk.Frame(self.root, bg=BG)
        box.pack(fill="both", expand=True, padx=16, pady=8)
        self.log_text = tk.Text(box, bg=CARD, fg=TEXT, insertbackground=TEXT,
                                font=("Consolas", 10), relief="flat", wrap="word")
        self.log_text.pack(fill="both", expand=True)
        self.log_text.configure(state="disabled")

        bottom = tk.Frame(self.root, bg=BG)
        bottom.pack(fill="x", padx=16, pady=(0, 14))
        self.start_btn = tk.Button(bottom, text="启动服务", width=14, height=2,
                                   bg=ACCENT, fg="#08130E", activebackground=ACCENT,
                                   font=("Segoe UI", 11, "bold"), relief="flat",
                                   command=self.toggle_server)
        self.start_btn.pack(side="left")
        self.receive_var = tk.BooleanVar(value=True)
        tk.Checkbutton(bottom, text="自动接收（推流）", variable=self.receive_var,
                       bg=BG, fg=TEXT, selectcolor=CARD, activebackground=BG,
                       activeforeground=TEXT, command=self._on_receive_toggle).pack(side="left", padx=12)
        tk.Button(bottom, text="放行防火墙", command=self.add_firewall).pack(side="right", padx=4)
        tk.Button(bottom, text="虚拟声卡帮助", command=self.show_virtual_help).pack(side="right", padx=4)
        tk.Button(bottom, text="清空日志", command=self.clear_log).pack(side="right", padx=4)

    def _kv(self, parent: tk.Frame, key: str, value: str, color: str) -> tk.Label:
        row = tk.Frame(parent, bg=CARD)
        row.pack(fill="x", padx=14, pady=3)
        tk.Label(row, text=key, bg=CARD, fg=MUTED, width=12, anchor="w",
                 font=("Segoe UI", 10)).pack(side="left")
        lbl = tk.Label(row, text=value, bg=CARD, fg=color, anchor="w", font=("Segoe UI", 10, "bold"))
        lbl.pack(side="left")
        return lbl

    # -------------------------------------------------- 交互

    def toggle_server(self) -> None:
        if self.server.running:
            self.server.stop()
            self.start_btn.configure(text="启动服务")
            self.state_label.configure(text="已停止", fg=WARN)
            self.phone_label.configure(text="—")
            self.device_label.configure(text="—")
            return
        self._apply_device()
        try:
            self.server.start()
            self.start_btn.configure(text="停止服务")
            self.state_label.configure(text="等待手机连接…", fg=WARN)
            self.ip_label.configure(text="  ".join(luomic.local_ipv4_list()))
        except OSError as exc:
            self.state_label.configure(text="启动失败", fg=ERR)
            messagebox.showerror("luo mic", f"服务启动失败：\n{exc}\n\n"
                                            "常见原因：端口 47777/47778/47779 被占用，或需要放行防火墙。")

    def _on_receive_toggle(self) -> None:
        value = bool(self.receive_var.get())
        self.server.set_auto_start(value)
        self.log("已切换到“开始接收”。" if value else "已切换到“暂停接收”。")

    def _on_device_change(self) -> None:
        self._apply_device()
        if not self.server.running:
            return
        self.log(f"已切换输出设备：{self.device_combo.get()}")
        # 关掉当前会话，手机会自动重连并按新设备重新推流
        self.server.close_session("切换输出设备")

    def _apply_device(self) -> None:
        """把下拉框的选择同步给服务端。"""
        self.server.device_index = self._selected_index()
        i = self.device_combo.current()
        self.server.device_name = None if i <= 0 else self.device_combo.get().replace("   ★虚拟声卡", "")

    def _selected_index(self) -> Optional[int]:
        i = self.device_combo.current()
        if i <= 0 or i - 1 >= len(self.devices):
            return None
        return int(self.devices[i - 1]["index"])

    def refresh_devices(self) -> None:
        self.devices = self.player.list_devices()
        names = ["系统默认输出设备"] + [
            d["name"] + ("   ★虚拟声卡" if d["virtual"] else "") for d in self.devices
        ]
        self.device_combo.configure(values=names)
        if self.device_combo.current() < 0:
            self.device_combo.current(0)
        virtual = [d for d in self.devices if d["virtual"]]
        self.virtual_hint.configure(
            text=("检测到虚拟声卡：" + virtual[0]["name"]) if virtual
            else "未检测到虚拟声卡（想要“手机当电脑麦克风”请安装 VB-CABLE）")
        self.log(f"检测到 {len(self.devices)} 个输出设备。")

    def add_firewall(self) -> None:
        self.log("正在请求管理员权限放行防火墙端口…（如弹出 UAC 请选择“是”）")
        ok = add_firewall_rules()
        self.log("防火墙已放行 UDP 47777 / TCP 47778-47779。" if ok else
                 "防火墙放行失败或被取消；请在 windows/tools 下以管理员身份运行 firewall.bat。")

    def show_virtual_help(self) -> None:
        text = (
            "让电脑里的其它软件（微信 / QQ / Discord / 游戏 / 直播）把手机当麦克风：\n\n"
            "1) 安装虚拟声卡 VB-CABLE（官网 vb-audio.com/Cable，或使用 Java 版界面的“安装虚拟声卡”）；\n"
            "2) 重启电脑；\n"
            "3) 本程序“播放到”选择 CABLE Input (VB-Audio Virtual Cable)；\n"
            "4) 目标软件里麦克风选择 CABLE Output (VB-Audio Virtual Cable)。\n\n"
            "想同时自己听到声音：Windows 声音设置 → 录制 → CABLE Output → 属性 → 侦听 → 勾选“侦听此设备”。\n\n"
            "没有虚拟声卡也能用：直接选扬声器/耳机，手机声音会从电脑音箱放出来。"
        )
        messagebox.showinfo("虚拟声卡帮助", text)

    def clear_log(self) -> None:
        self.log_text.configure(state="normal")
        self.log_text.delete("1.0", "end")
        self.log_text.configure(state="disabled")

    # -------------------------------------------------- 状态刷新

    def log(self, line: str) -> None:
        self.log_queue.put(line)

    def _pump(self) -> None:
        # 日志
        while True:
            try:
                line = self.log_queue.get_nowait()
            except queue.Empty:
                break
            if line is None:
                continue
            self.log_text.configure(state="normal")
            self.log_text.insert("end", f"[{time.strftime('%H:%M:%S')}] {line}\n")
            self.log_text.see("end")
            if int(self.log_text.index("end-1c").split(".")[0]) > 800:
                self.log_text.delete("1.0", "200.0")
            self.log_text.configure(state="disabled")
        # 状态
        while True:
            try:
                item = self.ui_queue.get_nowait()
            except queue.Empty:
                break
            if item[0] == "session":
                _, connected, name, ip = item
                if connected:
                    self.state_label.configure(text="已连接，正在传输", fg=ACCENT)
                    self.phone_label.configure(text=f"{name} @ {ip}")
                else:
                    self.state_label.configure(text="等待手机连接…" if self.server.running else "已停止", fg=WARN)
                    self.phone_label.configure(text="—")
            elif item[0] == "stats":
                _, text, db, streaming = item
                self.stats_label.configure(text=text)
                self.level = max(0.0, min(100.0, (db + 60) / 60 * 100))
                self._draw_level()
        if self.server.running:
            self.device_label.configure(text=self.player.device_name if self.server.has_client
                                        else (self.device_combo.get() or "系统默认输出设备"))
        self.root.after(250, self._pump)

    def _draw_level(self) -> None:
        w = max(10, self.canvas.winfo_width())
        h = max(6, self.canvas.winfo_height())
        self.canvas.delete("all")
        self.canvas.create_rectangle(0, 0, w, h, fill=CARD, outline="")
        self.canvas.create_rectangle(0, 0, int(w * self.level / 100), h, fill=ACCENT, outline="")

    def _on_close(self) -> None:
        try:
            self.server.stop()
        finally:
            self.root.destroy()


# ---------------------------------------------------------------- Windows 集成


def add_firewall_rules() -> bool:
    """调用 netsh 放行端口（会弹 UAC）。仅 Windows 有效。"""
    import os
    import subprocess
    import tempfile

    if os.name != "nt":
        return False

    cmds = [
        'netsh advfirewall firewall delete rule name="luo mic TCP 47778-47779"',
        'netsh advfirewall firewall delete rule name="luo mic UDP 47777"',
        'netsh advfirewall firewall add rule name="luo mic UDP 47777" dir=in action=allow protocol=UDP localport=47777',
        'netsh advfirewall firewall add rule name="luo mic TCP 47778-47779" dir=in action=allow protocol=TCP localport=47778-47779',
    ]
    script = "\r\n".join(["@echo off", "chcp 65001 >nul"] + cmds + ["exit /b 0"])

    path = os.path.join(tempfile.gettempdir(), "luomic-firewall.bat")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(script)
    try:
        ps = (f"Start-Process -FilePath '{path}' -Verb RunAs -WindowStyle Hidden -Wait")
        proc = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps],
                              capture_output=True)
        return proc.returncode == 0
    except Exception:
        return False


def main(argv: List[str]) -> int:
    if "--list-devices" in argv:
        player = AudioOutput(print)
        print("可用输出设备：")
        for d in player.list_devices():
            print(f"  [{d['index']}] {d['name']}" + ("   <-- 虚拟声卡" if d["virtual"] else ""))
        print("\n默认设备：" + AudioOutput.default_device_name())
        return 0

    import tkinter as tk

    root = tk.Tk()
    app = App(root)
    root.mainloop()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
