"""Self-contained HTTP demonstration. Requires only the Python bundled in the image."""
import concurrent.futures
import copy
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request
import uuid

FIXTURES = Path(__file__).parent / "fixtures"
GO = os.environ.get("GO_URL", "http://go-ingestion:8080").rstrip("/")
SCALA = os.environ.get("SCALA_URL", "http://scala-analysis:8081").rstrip("/")
ID_FIELDS = {"eventId", "matchId", "playerId", "capturedPlayerId", "winnerPlayerId",
             "scorerId", "affectedPlayerId", "teamId", "shotEventId", "moveEventId", "stopId", "powerUpEventId",
             "pelletId", "entryEventId", "exitEventId", "chainEventId", "startEventId",
             "endEventId", "thresholdEventId"}
ID_LISTS = {"driverIds", "playerIds", "moveEventIds", "ghostEventIds", "shotEventIds"}


def namespace(value, prefix="demo-", key=""):
    if isinstance(value, dict):
        return {k: namespace(v, prefix, k) for k, v in value.items()}
    if isinstance(value, list):
        return [namespace(v, prefix, key) for v in value]
    if isinstance(value, str) and (key in ID_FIELDS or key in ID_LISTS):
        return prefix + value
    return value


def remap(value, mapping):
    if isinstance(value, dict):
        return {k: remap(v, mapping) for k, v in value.items()}
    if isinstance(value, list):
        return [remap(v, mapping) for v in value]
    return mapping.get(value, value) if isinstance(value, str) else value


def request(method, base, path, body=None, statuses=(200,)):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read()
    if status not in statuses:
        raise AssertionError(f"{method} {path}: HTTP {status}, esperado {statuses}: {raw.decode(errors='replace')}")
    return status, json.loads(raw) if raw else None


def get(path):
    return request("GET", SCALA, path)[1]


def ready(base):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            request("GET", base, "/healthz")
            return
        except (OSError, AssertionError):
            time.sleep(0.5)
    raise AssertionError(f"Servicio no disponible: {base}")


def equal(actual, expected, label):
    if actual != expected:
        raise AssertionError(f"{label}: esperado {json.dumps(expected, sort_keys=True)}, obtenido {json.dumps(actual, sort_keys=True)}")


def send(event):
    status, _ = request("POST", GO, "/api/v1/events", event, (201, 409))
    if status == 409:
        # A duplicate is acceptable only if Go actually retained exactly this event.
        batch = request("GET", GO, "/api/v1/matches/" + event["matchId"])[1]
        equal([e for e in batch["events"] if e["eventId"] == event["eventId"]], [event], "Duplicado idéntico")


def run():
    passed = []

    def ok(message):
        passed.append(message)
        print("OK: " + message, flush=True)

    ready(GO)
    ready(SCALA)
    ok("Go y Scala disponibles")
    events = [json.loads(line) for line in (FIXTURES / "events.jsonl").read_text().splitlines() if line.strip()]
    groups = {}
    for event in events:
        groups.setdefault(event["matchId"], []).append(event)
    expected = {}
    for match_id in groups:
        name = ("go-" if match_id.startswith("pacman-") else "") + match_id + ".expected.json"
        expected[match_id] = json.loads((FIXTURES / "expected" / name).read_text())
    # Preserve the original inconsistent fixture for a separate negative test.
    original_race = copy.deepcopy(groups["race-3"])
    groups["race-3"][-1]["data"]["classification"] = copy.deepcopy(expected["race-3"]["classification"])
    for event in events:
        send(namespace(event))
    for match_id, result in expected.items():
        equal(get("/api/v1/matches/demo-" + match_id), namespace(result), "Resultado completo " + match_id)
    ok("12 partidas de los cuatro juegos: eventos Go -> Scala y resultados completos correctos")

    extra = json.loads((FIXTURES / "extra" / "race-comeback.json").read_text())
    extra_expected = json.loads((FIXTURES / "extra" / "race-comeback.expected.json").read_text())
    for event in extra["events"]:
        send(namespace(event))
    equal(get("/api/v1/matches/demo-race-comeback"), namespace(extra_expected), "Carrera con remontada y sanciones")
    assert get("/api/v1/matches/demo-chess-1")["players"][0]["timePressure"] is True
    assert get("/api/v1/matches/demo-chess-2")["players"][0]["timePressure"] is False
    assert len(get("/api/v1/matches/demo-basket-2")["scoringRuns"]) == 1
    assert get("/api/v1/matches/demo-basket-1")["scoringRuns"] == []
    assert get("/api/v1/matches/demo-race-comeback")["drivers"][0]["comeback"] is True
    assert get("/api/v1/matches/demo-race-1")["drivers"][0]["comeback"] is False
    assert get("/api/v1/matches/demo-pacman-1")["powerEffects"][0]["chainEventId"] is not None
    assert get("/api/v1/matches/demo-pacman-2")["powerEffects"][0]["chainEventId"] is None
    ok("Patrones positivos y negativos: presión, racha, remontada y cadena de fantasmas")

    first = groups["pacman-1"]
    mapping = {"pacman-1": "pacman-repeat", **{e["eventId"]: "repeat-" + e["eventId"] for e in first}}
    repeat = namespace(remap(first, mapping))
    for event in repeat:
        send(event)
    equal(get("/api/v1/matches/demo-pacman-repeat"), namespace(remap(expected["pacman-1"], mapping)), "Segunda partida del mismo jugador")
    for player, count, score, best in [("pac-alice", 2, 2920, 1460), ("pac-bob", 1, 60, 60), ("pac-carol", 1, 3050, 3050)]:
        equal(get("/api/v1/pacman/players/demo-" + player),
              {"playerId": "demo-" + player, "completedMatches": count, "totalScore": score, "bestScore": best}, "Acumulados " + player)
    colors = get("/api/v1/chess/colors")
    assert {r["color"] for r in colors} == {"WHITE", "BLACK"}
    for row in colors:
        assert row["completedMatches"] >= 3 and row["wins"] >= 1
        assert abs(row["winPercentage"] - 100 * row["wins"] / row["completedMatches"]) < 0.000001
    ok("Acumulados entre partidas y porcentajes por color correctos")

    baseline = get("/api/v1/pacman/players/demo-pac-alice")
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(lambda _: [send(event) for event in repeat], range(4)))
    equal(get("/api/v1/pacman/players/demo-pac-alice"), baseline, "Reenvíos sin duplicar puntos")
    equal(get("/api/v1/chess/colors"), colors, "Reenvíos sin duplicar partidas")
    ok("Reenvíos concurrentes idénticos sin duplicar resultados")

    negative_prefix = "negative-" + uuid.uuid4().hex + "-"
    for fixture in sorted((FIXTURES / "invalid").iterdir()):
        bad = namespace([json.loads(line) for line in fixture.read_text().splitlines() if line.strip()], negative_prefix + fixture.stem + "-")
        rejected = False
        for event in bad:
            status, _ = request("POST", GO, "/api/v1/events", event, (201, 400, 409, 422))
            if status != 201:
                rejected = True
                break
        assert rejected, "Fixture inválido aceptado: " + fixture.name
        for match_id in {e["matchId"] for e in bad}:
            request("GET", SCALA, "/api/v1/matches/" + match_id, statuses=(404,))
    bad_race = namespace(original_race, negative_prefix)
    for event in bad_race[:-1]:
        request("POST", GO, "/api/v1/events", event, (201,))
    request("POST", GO, "/api/v1/events", bad_race[-1], (502,))
    request("GET", SCALA, "/api/v1/matches/" + bad_race[0]["matchId"], statuses=(404,))
    ok("10 fixtures inválidos y clasificación inconsistente rechazados sin resultados parciales")
    print("DEMOSTRACION APROBADA: 14 resultados, cuatro juegos y todos los casos comprobados.", flush=True)
    print("DEMO_RESULT " + json.dumps({"passed": True, "groups": passed, "results": 14}), flush=True)


if __name__ == "__main__":
    try:
        run()
    except Exception as error:
        print(f"DEMOSTRACION FALLIDA: {error}", file=sys.stderr, flush=True)
        print('DEMO_RESULT {"passed": false}', flush=True)
        sys.exit(1)
