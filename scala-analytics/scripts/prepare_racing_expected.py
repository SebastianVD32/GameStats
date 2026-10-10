"""Hand-calculated complete results for the unchanged Go racing fixtures."""
import json
from pathlib import Path

cases = [
    (1, [('race-alice', 90000, 0, 1, 1, False), ('race-bob', 91000, 0, 2, 2, False)],
     5000, 'race-alice', 'r1-e2', 90000),
    (2, [('race-carol', 95000, 0, 2, 2, False), ('race-dan', 93000, 0, 1, 1, False)],
     9000, 'race-dan', 'r2-e5', 93000),
    (3, [('race-alice', 90000, 0, 3, 2, False), ('race-dan', 88000, 5000, 1, 3, False),
         ('race-erin', 89000, 0, 2, 1, False)], 13000, 'race-dan', 'r3-e4', 88000),
]
for n, rows, duration, fastest_id, fastest_event, fastest_time in cases:
    drivers = []
    for pid, time, penalty, before, after, comeback in rows:
        stops = []
        if pid == 'race-carol':
            stops = [dict(stopId='r2-stop-1', entryEventId='r2-e2', exitEventId='r2-e3',
                          entryLapNumber=1, exitLapNumber=1, durationMs=1000)]
        drivers.append(dict(playerId=pid, completedLaps=1, bestLapTimeMs=time,
                            averageLapTimeMs=float(time), totalTimeMs=time, pitStops=len(stops), stops=stops,
                            timePenaltyMs=penalty, positionPenalty=0, adjustedTimeMs=time + penalty,
                            positionWithoutPenalties=before, finalPosition=after,
                            positionChange=before-after, comeback=comeback))
    def classification(column):
        return [dict(playerId=r[0], position=r[column]) for r in sorted(rows, key=lambda r: r[column])]
    result = dict(gameId='RACING', gameVersion='1.0', matchId=f'race-{n}', durationMs=duration,
                  drivers=drivers, classificationWithoutPenalties=classification(3), classification=classification(4),
                  fastestLaps=[dict(playerId=fastest_id, eventId=fastest_event, lapNumber=1, lapTimeMs=fastest_time)])
    (Path(__file__).resolve().parents[1] / 'testdata' / f'race-{n}.expected.json').write_text(
        json.dumps(result, indent=2) + '\n', encoding='utf-8')
