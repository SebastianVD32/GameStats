"""Independent, hand-calculated oracle for the three unchanged Go fixtures."""
import json
from pathlib import Path

def shooting(made=0, attempted=0):
    return dict(made=made, attempted=attempted,
                percentage=made * 100 / attempted if attempted else None)

def player(n, side, index):
    return dict(playerId=f'b{n}-{side[0]}{index}', teamId=f'b{n}-{side}', points=0,
                assists=0, rebounds=0, offensiveRebounds=0, defensiveRebounds=0,
                turnovers=0, fouls=0, fieldGoals=shooting(), threePointers=shooting(),
                freeThrows=shooting(), assistedBasketPoints=0, pointsCreatedByAssists=0,
                excluded=False, fifthFoul=None)

def team(n, side):
    return dict(teamId=f'b{n}-{side}', points=0, assistedBasketPoints=0,
                fieldGoals=shooting(), threePointers=shooting(), freeThrows=shooting())

for n in (1, 2, 3):
    ps = [player(n, side, i) for side in ('home', 'away') for i in (1, 2)]
    ts = [team(n, side) for side in ('home', 'away')]
    runs = []
    if n == 1:
        ps[0].update(points=2, rebounds=1, defensiveRebounds=1,
                     fieldGoals=shooting(1, 1), assistedBasketPoints=2)
        ps[1].update(assists=1, pointsCreatedByAssists=2)
        ps[2].update(fieldGoals=shooting(0, 1), threePointers=shooting(0, 1))
        ts[0].update(points=2, assistedBasketPoints=2, fieldGoals=shooting(1, 1))
        ts[1].update(fieldGoals=shooting(0, 1), threePointers=shooting(0, 1))
    if n == 2:
        ps[0].update(points=5, fieldGoals=shooting(2, 2), threePointers=shooting(1, 1))
        ps[1].update(points=3, fieldGoals=shooting(1, 1), threePointers=shooting(1, 1))
        ts[0].update(points=8, fieldGoals=shooting(3, 3), threePointers=shooting(2, 2))
        runs = [dict(teamId='b2-home', points=8, shotEventIds=['b2-e2', 'b2-e3', 'b2-e4'],
                     startEventId='b2-e2', endEventId='b2-e4', thresholdEventId='b2-e4')]
    if n == 3:
        ps[0].update(fouls=5, excluded=True, fifthFoul=dict(
            eventId='b3-e6', sequence=6, timestamp='2026-09-01T12:00:16Z', period=4))
    result = dict(gameId='BASKETBALL', gameVersion='1.0', matchId=f'basket-{n}',
                  durationMs={1: 7000, 2: 10000, 3: 15000}[n], players=ps, teams=ts, scoringRuns=runs)
    (Path(__file__).resolve().parents[1] / 'testdata' / f'basket-{n}.expected.json').write_text(
        json.dumps(result, indent=2) + '\n', encoding='utf-8')
