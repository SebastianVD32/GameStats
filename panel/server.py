"""Local teaching panel: fixed upstream routes, shared demo, persistent run history."""
import copy
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import threading
import urllib.error
import urllib.parse
import urllib.request
import uuid

import demo

DATA = Path(os.environ.get("PANEL_DATA", "/data/panel"))
STATIC = Path(__file__).parent / "static"
LOCK = threading.RLock()
ACTIVE = None
STAGES = ["Disponibilidad", "Doce partidas", "Patrones positivos y negativos",
          "Acumulados", "Reenvíos concurrentes", "Casos inválidos"]


def stamp():
    return datetime.now(timezone.utc).isoformat()


def save(run):
    DATA.mkdir(parents=True, exist_ok=True)
    path = DATA / (run["id"] + ".json")
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(run, ensure_ascii=False), encoding="utf-8")
    temporary.replace(path)


def load(run_id):
    if not re.fullmatch(r"[a-f0-9]{32}", run_id):
        raise FileNotFoundError("Ejecución inexistente")
    return json.loads((DATA / (run_id + ".json")).read_text(encoding="utf-8"))


def history():
    return sorted([load(p.stem) for p in DATA.glob("*.json")], key=lambda r: r["started"], reverse=True)


def recover():
    DATA.mkdir(parents=True, exist_ok=True)
    for run in history():
        if run["status"] == "running":
            run.update(status="interrupted", error="El panel se reinició. Las comprobaciones pendientes no se ejecutaron.", finished=stamp())
            save(run)


def upstream(base, path):
    try:
        with urllib.request.urlopen(base + path, timeout=10) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, {"error": "El servicio devolvió HTTP " + str(error.code)}


def worker(run):
    global ACTIVE

    def observe(kind, payload):
        with LOCK:
            if kind == "catalog":
                run["matches"] = payload["matches"]
            elif kind == "check":
                run["checks"].append(payload)
            elif kind == "http":
                run["requests"].append(payload)
                if payload["eventId"]:
                    key = "accepted" if payload["status"] == 201 else "rejected"
                    run[key] += 1
            elif kind == "group":
                run["groups"].append(payload["message"])
            # Persist coarse checkpoints; GET sees the live record under the same lock.
            if kind in ("catalog", "group"):
                save(run)

    try:
        demo.run(prefix="web-" + run["id"] + "-", observer=observe)
        with LOCK:
            run["status"] = "passed"
    except Exception as error:
        with LOCK:
            run.update(status="failed", error=str(error))
    finally:
        with LOCK:
            run["finished"] = stamp()
            try:
                save(run)
            finally:
                ACTIVE = None
                demo.OBSERVER = None


def start():
    global ACTIVE
    with LOCK:
        if ACTIVE is not None:
            return None
        run = {"id": uuid.uuid4().hex, "started": stamp(), "finished": None,
               "status": "running", "error": None, "accepted": 0, "rejected": 0,
               "groups": [], "stages": STAGES, "matches": [], "checks": [], "requests": []}
        save(run)
        ACTIVE = run
        threading.Thread(target=worker, args=(run,), daemon=True).start()
        return copy.deepcopy(run)


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, payload, content_type="application/json; charset=utf-8"):
        data = json.dumps(payload, ensure_ascii=False).encode() if content_type.startswith("application/json") else payload
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        try:
            self.get()
        except FileNotFoundError:
            self.reply(404, {"error": "No hay datos para esta dirección"})
        except (OSError, ValueError) as error:
            self.reply(502, {"error": "No se pudo consultar el servicio: " + str(error)})

    def get(self):
        path = urllib.parse.urlsplit(self.path).path
        if path == "/healthz":
            return self.reply(200, {"status": "ok"})
        if path == "/api/health":
            services = {}
            for name, base in (("Go", demo.GO), ("Scala", demo.SCALA)):
                try:
                    status, _ = upstream(base, "/healthz")
                    services[name] = {"available": status == 200, "http": status}
                except (OSError, ValueError):
                    services[name] = {"available": False, "http": None}
            return self.reply(200, services)
        if path == "/api/runs":
            with LOCK:
                runs = history()
                if ACTIVE:
                    runs = [copy.deepcopy(ACTIVE)] + [r for r in runs if r["id"] != ACTIVE["id"]]
                return self.reply(200, [{k: r[k] for k in ("id", "started", "status")} for r in runs])
        if path.startswith("/api/runs/"):
            run_id = path.rsplit("/", 1)[-1]
            with LOCK:
                run = copy.deepcopy(ACTIVE) if ACTIVE and ACTIVE["id"] == run_id else load(run_id)
            return self.reply(200, run)
        routes = (("/api/results/", demo.SCALA, "/api/v1/matches/"),
                  ("/api/events/", demo.GO, "/api/v1/matches/"),
                  ("/api/players/", demo.SCALA, "/api/v1/pacman/players/"))
        for prefix, base, target in routes:
            if path.startswith(prefix):
                identity = path[len(prefix):]
                if not re.fullmatch(r"[A-Za-z0-9_-]{1,200}", identity):
                    return self.reply(400, {"error": "Identificador inválido"})
                status, payload = upstream(base, target + identity)
                return self.reply(status, payload)
        if path == "/api/colors":
            status, payload = upstream(demo.SCALA, "/api/v1/chess/colors")
            return self.reply(status, payload)
        assets = {"/": ("index.html", "text/html; charset=utf-8"),
                  "/app.js": ("app.js", "text/javascript; charset=utf-8"),
                  "/style.css": ("style.css", "text/css; charset=utf-8")}
        if path not in assets:
            raise FileNotFoundError()
        name, kind = assets[path]
        return self.reply(200, (STATIC / name).read_bytes(), kind)

    def do_POST(self):
        if self.path != "/api/runs":
            return self.reply(404, {"error": "Ruta inexistente"})
        # Only same-origin UI requests; no shell, arbitrary URL, Docker socket or custom events.
        if self.headers.get("X-GameStats") != "panel":
            return self.reply(403, {"error": "Solicitud fuera del panel"})
        origin = self.headers.get("Origin")
        if origin and origin not in ("http://" + self.headers.get("Host", ""), "https://" + self.headers.get("Host", "")):
            return self.reply(403, {"error": "Origen no permitido"})
        if self.headers.get("Content-Length", "0") != "0" or self.headers.get("Transfer-Encoding"):
            return self.reply(400, {"error": "Este comando no acepta datos personalizados"})
        try:
            run = start()
        except OSError:
            return self.reply(503, {"error": "No se pudo guardar la ejecución"})
        self.reply(202 if run else 409, run or {"error": "Ya hay una demostración en curso"})


if __name__ == "__main__":
    recover()
    ThreadingHTTPServer(("0.0.0.0", 8090), Handler).serve_forever()
