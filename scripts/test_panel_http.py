"""Acceptance against real container services, independent of browser tooling."""
import json
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request


def verify(base, output, history_only=False):
    def call(path, method="GET", expected=200, headers=None):
        req = urllib.request.Request(base + path, method=method, headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        assert status == expected, (path, status, raw[:500])
        return json.loads(raw) if raw else None

    if history_only:
        previous = json.loads(output.read_text())
        for run_id in previous["runs"]:
            assert call("/api/runs/" + run_id)["status"] == "passed"
        result = call("/api/results/" + previous["match"])
        assert result["totalMoves"] == 5 and result["totalCaptures"] == 1
        print("PASS: panel history and Scala results survive panel restart")
        return

    assert all(s["available"] for s in call("/api/health").values())
    call("/api/runs", method="POST", expected=403)
    call("/api/runs", method="POST", expected=403,
         headers={"X-GameStats": "panel", "Origin": "https://other.invalid"})
    call("/api/runs/not-a-run", expected=404)
    call("/api/results/nonexistent-match", expected=404)
    call("/api/results/%2Fetc", expected=400)
    runs = []
    match_sets = []
    for _ in range(2):
        run = call("/api/runs", method="POST", expected=202, headers={"X-GameStats": "panel"})
        runs.append(run["id"])
        call("/api/runs", method="POST", expected=409, headers={"X-GameStats": "panel"})
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            run = call("/api/runs/" + run["id"])
            if run["status"] != "running":
                break
            time.sleep(.25)
        assert run["status"] == "passed", run.get("error")
        assert len(run["groups"]) == 6
        assert run["accepted"] > 67 and run["rejected"] > 10
        assert len(run["checks"]) > 100 and all(c["passed"] for c in run["checks"])
        assert any(c["scenario"] == "Sin presión de tiempo" and c["expected"] is False for c in run["checks"])
        assert any(502 in r["expected"] and r["status"] == 502 for r in run["requests"])
        assert {m["game"] for m in run["matches"]} == {"CHESS", "BASKETBALL", "RACING", "PACMAN"}
        assert len(run["matches"]) == 14
        ids = {m["id"] for m in run["matches"]}
        match_sets.append(ids)
        for item in run["matches"]:
            result = call("/api/results/" + item["id"])
            assert result["matchId"] == item["id"] and result["gameId"] == item["game"]
        pac = call("/api/players/web-" + run["id"] + "-pac-alice")
        assert pac["completedMatches"] == 2 and pac["totalScore"] == 2920
    assert not match_sets[0] & match_sets[1]
    for match in match_sets[0]:
        assert call("/api/results/" + match)["matchId"] == match
    recorded = {r["id"] for r in call("/api/runs")}
    assert set(runs) <= recorded
    report = {"passed": True, "runs": runs, "match": "web-" + runs[0] + "-chess-1"}
    output.write_text(json.dumps(report), encoding="utf-8")
    print("PASS: real panel demos, 28 unique results, independent expectations, rejection cases and safe routes")


if __name__ == "__main__":
    verify(sys.argv[1], Path(sys.argv[2]), "--verify-history" in sys.argv)
